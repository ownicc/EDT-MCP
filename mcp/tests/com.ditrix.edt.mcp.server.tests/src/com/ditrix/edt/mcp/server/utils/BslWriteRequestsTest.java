package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BslWriteRequestsTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String DIGEST = "a".repeat(64);
    private static final class TestClock extends Clock
    {
        Instant now = Instant.parse("2026-11-09T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    @Test public void durableAdmissionPrecedesWorkAndTerminalReplayRunsOnce() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        var store = new BslWriteRequests(directory, Clock.systemUTC());
        AtomicInteger calls = new AtomicInteger();
        String first = store.execute("../request", DIGEST, () -> {
            calls.incrementAndGet();
            try (var files = Files.list(directory)) { assertEquals(1, files.filter(p -> p.toString().endsWith(".json")).count()); }
            catch (Exception e) { throw new AssertionError(e); }
            return "---\nstatus: success\n---\nFile written successfully";
        });
        var result = json(first);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertTrue(result.get("mutationCommitted").getAsBoolean());
        assertEquals("applied", result.get("status").getAsString());
        assertEquals("../request", result.get("requestId").getAsString());
        assertTrue(result.has("retainedUntil"));
        assertEquals(first, store.execute("../request", DIGEST, () -> { fail("must replay"); return null; }));
        assertEquals(first, new BslWriteRequests(directory, Clock.systemUTC()).getByRequestId("../request"));
        assertEquals(1, calls.get());
        String conflict = store.execute("../request", "b".repeat(64), () -> { fail("conflict must not run"); return null; });
        assertEquals("requestIdConflict", json(conflict).get("code").getAsString());
    }

    @Test public void concurrentDuplicatesReturnInProgressAndNeverRunTwice() throws Exception
    {
        var store = new BslWriteRequests(temporary.newFolder().toPath(), Clock.systemUTC());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            var first = executor.submit(() -> store.execute("r", DIGEST, () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return "written";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            String duplicate = store.execute("r", DIGEST, () -> { fail("must not run duplicate"); return null; });
            assertEquals("inProgress", json(duplicate).get("status").getAsString());
            assertTrue(json(duplicate).get("mutationOutcomeUnknown").getAsBoolean());
            release.countDown();
            assertEquals("applied", json(first.get(5, TimeUnit.SECONDS)).get("status").getAsString());
        }
        finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void restartInterruptionsStayUnknownAndNeverAutomaticallyRetry() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        var live = new BslWriteRequests(directory, Clock.systemUTC());
        live.execute("r", DIGEST, () -> {
            try
            {
                var recovered = new BslWriteRequests(directory, Clock.systemUTC());
                String replay = recovered.execute("r", DIGEST, () -> { fail("recovery must fence"); return null; });
                assertEquals("unknown", json(replay).get("status").getAsString());
                assertTrue(json(replay).get("mutationOutcomeUnknown").getAsBoolean());
                assertEquals("interrupted", json(replay).get("recovery").getAsString());
            }
            catch (Exception e) { throw new AssertionError(e); }
            return "written";
        });
    }

    @Test public void reviewObserverPersistsTerminalAndDoesNotLoseARacingDecision() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        var store = new BslWriteRequests(directory, Clock.systemUTC());
        try (var reviews = new BslWriteReviews(Clock.systemUTC(), Runnable::run))
        {
            String id = reviews.register(BslWriteReviewsTest.write("a.bsl", "secret-source"), "x", (key, value) -> null).snapshot().reviewId();
            String response = store.execute("r", DIGEST, () -> {
                store.observeReview("r", reviews, id);
                String stale = reviews.get(id).toJson();
                reviews.cancel(id);
                return stale;
            });
            assertEquals("cancelled", json(response).get("status").getAsString());
            var recovered = new BslWriteRequests(directory, Clock.systemUTC());
            assertEquals(response, recovered.getByReviewId(id));
            assertEquals(response, recovered.getByRequestId("r"));
            try (var files = Files.list(directory))
            {
                for (Path path : files.toList()) assertFalse(Files.readString(path).contains("secret-source"));
            }
        }
    }

    @Test public void failedAdmissionCannotExecuteAndFailedCompletionStaysFenced() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        var store = new BslWriteRequests(directory, Clock.systemUTC());
        String key = PreparedBslWrite.sha256("r".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path receipt = directory.resolve(key + ".json");
        Files.createDirectory(receipt);
        String failure = store.execute("r", DIGEST, () -> { fail("admission must be durable first"); return null; });
        assertFalse(json(failure).get("success").getAsBoolean());
        Files.delete(receipt);
        AtomicInteger calls = new AtomicInteger();
        String committed = store.execute("r", DIGEST, () -> {
            calls.incrementAndGet();
            try { Files.delete(receipt); Files.createDirectory(receipt); }
            catch (Exception e) { throw new AssertionError(e); }
            return "written";
        });
        assertTrue(json(committed).get("mutationCommitted").getAsBoolean());
        assertTrue(json(committed).get("receiptPersistenceFailed").getAsBoolean());
        store.execute("r", DIGEST, () -> { fail("completion I/O failure cannot reopen admission"); return null; });
        assertEquals(1, calls.get());
        try { new BslWriteRequests(directory, Clock.systemUTC()); fail("corrupt fence cannot be ignored"); }
        catch (java.io.IOException expected) { }
    }

    @Test public void expiredKnownIdRefusesAndOnlyExpiredReceiptsReleaseCapacity() throws Exception
    {
        TestClock clock = new TestClock();
        Path directory = temporary.newFolder().toPath();
        var store = new BslWriteRequests(directory, clock);
        store.execute("r", DIGEST, () -> "written");
        clock.now = clock.now.plus(Duration.ofDays(7));
        String expired = store.execute("r", DIGEST, () -> { fail("known expired ID must refuse"); return null; });
        assertEquals("requestIdExpired", json(expired).get("code").getAsString());
        store.execute("new", DIGEST, () -> "written");
        try (var files = Files.list(directory)) { assertEquals(1, files.filter(p -> p.toString().endsWith(".json")).count()); }
    }

    @Test public void oversizedResponsesStayBoundedWithoutLosingMutationTruth() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        var store = new BslWriteRequests(directory, Clock.systemUTC());
        String result = store.execute("r", DIGEST, () -> "x".repeat(100_000));
        assertTrue(json(result).get("mutationCommitted").getAsBoolean());
        assertTrue(json(result).get("resultTruncated").getAsBoolean());
        try (var files = Files.list(directory))
        { for (Path path : files.toList()) assertTrue(Files.size(path) <= 65_536); }
        assertEquals(result, new BslWriteRequests(directory, Clock.systemUTC()).getByRequestId("r"));
    }
    @Test public void capacityRefusesInsteadOfEvictingRetainedReceipts() throws Exception
    {
        var store = new BslWriteRequests(temporary.newFolder().toPath(), Clock.systemUTC());
        for (int i = 0; i < 1000; i++)
            assertTrue(json(store.execute("r" + i, DIGEST, () -> "written")).get("success").getAsBoolean());
        String full = store.execute("overflow", DIGEST, () -> { fail("full store must not execute"); return null; });
        assertEquals("requestCapacity", json(full).get("code").getAsString());
        assertEquals("applied", json(store.execute("r0", DIGEST, () -> { fail("retained receipt cannot be evicted"); return null; })).get("status").getAsString());
    }

    @Test public void corruptAndOversizedReceiptFilesRefuseToOpen() throws Exception
    {
        for (String content : java.util.List.of("{broken", "x".repeat(65_537)))
        {
            Path directory = temporary.newFolder().toPath();
            Files.writeString(directory.resolve("a".repeat(64) + ".json"), content);
            try { new BslWriteRequests(directory, Clock.systemUTC()); fail("bad receipts must fail closed"); }
            catch (java.io.IOException expected) { }
        }
    }

    @Test public void decisionAfterOriginalCallReturnsUpdatesTheDurableReceipt() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        var store = new BslWriteRequests(directory, Clock.systemUTC());
        try (var reviews = new BslWriteReviews(Clock.systemUTC(), Runnable::run))
        {
            String id = reviews.register(BslWriteReviewsTest.write("a.bsl", "new"), "x", (key, value) -> {
                assertTrue(reviews.tryEnterMutation(key));
                return new BslWriteReviews.Outcome(BslWriteReviews.State.APPLIED, true, "written", null);
            }).snapshot().reviewId();
            String pending = store.execute("r", DIGEST, () -> {
                store.observeReview("r", reviews, id);
                return reviews.get(id).toJson();
            });
            assertEquals("awaitingApproval", json(pending).get("status").getAsString());
            reviews.apply(id);
            var recovered = new BslWriteRequests(directory, Clock.systemUTC());
            String completed = recovered.getByReviewId(id);
            assertEquals("applied", json(completed).get("status").getAsString());
            assertTrue(json(completed).get("mutationCommitted").getAsBoolean());
            assertEquals(completed, recovered.execute("r", DIGEST, () -> { fail("completed review must replay"); return null; }));
        }
    }

    @Test public void recoveredPendingReviewIsUnknownAndStillAddressableByReviewId() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        var store = new BslWriteRequests(directory, Clock.systemUTC());
        try (var reviews = new BslWriteReviews(Clock.systemUTC(), Runnable::run))
        {
            String id = reviews.register(BslWriteReviewsTest.write("a.bsl", "new"), "x", (key, value) -> null).snapshot().reviewId();
            store.execute("r", DIGEST, () -> {
                store.observeReview("r", reviews, id);
                return reviews.get(id).toJson();
            });
            var recovered = new BslWriteRequests(directory, Clock.systemUTC());
            String unknown = recovered.getByReviewId(id);
            assertEquals("unknown", json(unknown).get("status").getAsString());
            assertEquals("r", json(unknown).get("requestId").getAsString());
            assertTrue(json(unknown).get("mutationOutcomeUnknown").getAsBoolean());
            assertEquals(unknown, recovered.execute("r", DIGEST, () -> { fail("pending recovery must not recreate review"); return null; }));
        }
    }

}
