package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class BslWriteReviewsTest
{
    private static final class TestClock extends Clock
    {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    static PreparedBslWrite write(String path, String source)
    {
        return new PreparedBslWrite("Project", "file:/project", 0, path, "file:/project/src/" + path,
            true, PreparedBslWrite.sha256("old".getBytes(StandardCharsets.UTF_8)), "old", source,
            source.getBytes(StandardCharsets.UTF_8), 1, 1, "replace", false, "");
    }

    @Test
    public void applyAtDeadlineCannotCommitBeforeExpiryTimerRuns()
    {
        TestClock clock = new TestClock();
        AtomicInteger calls = new AtomicInteger();
        try (BslWriteReviews reviews = new BslWriteReviews(clock, Runnable::run))
        {
            String id = reviews.register(write("a.bsl", "new"), "digest", (key, value) -> {
                calls.incrementAndGet();
                return new BslWriteReviews.Outcome(BslWriteReviews.State.APPLIED, true, "written", null);
            }).snapshot().reviewId();
            clock.now = clock.now.plus(Duration.ofMinutes(30));
            reviews.apply(id);
            assertEquals(BslWriteReviews.State.EXPIRED, reviews.get(id).status());
            assertEquals(0, calls.get());
        }
    }

    @Test
    public void deduplicatesOnlyMatchingActiveRequestsAndRefusesOtherChanges()
    {
        try (BslWriteReviews reviews = new BslWriteReviews(Clock.systemUTC(), Runnable::run))
        {
            var first = reviews.register(write("a.bsl", "new"), "one", (id, value) -> null);
            var same = reviews.register(write("a.bsl", "new"), "one", (id, value) -> null);
            assertTrue(same.reused());
            assertEquals(first.snapshot().reviewId(), same.snapshot().reviewId());
            var other = reviews.register(write("a.bsl", "other"), "two", (id, value) -> null);
            assertTrue(other.errorJson(), other.errorJson().contains("reviewPending"));
            assertTrue(other.errorJson(), other.errorJson().contains(first.snapshot().reviewId()));
        }
    }

    @Test
    public void activeAndTerminalBoundsDoNotEvictPendingWork()
    {
        try (BslWriteReviews reviews = new BslWriteReviews(Clock.systemUTC(), Runnable::run))
        {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 16; i++)
                ids.add(reviews.register(write(i + ".bsl", "new"), "x", (id, value) -> null).snapshot().reviewId());
            assertNotNull(reviews.register(write("overflow.bsl", "new"), "x", (id, value) -> null).errorJson());
            for (String id : ids) reviews.reject(id);
            for (int i = 0; i < 101; i++)
            {
                String id = reviews.register(write("a.bsl", "new"), "x", (key, value) -> null).snapshot().reviewId();
                reviews.reject(id);
            }
            assertNull(reviews.get(ids.get(0)));
            assertNotNull(reviews.register(write("a.bsl", "new"), "x", (id, value) -> null).snapshot());
        }
    }

    @Test
    public void countsUtf8PayloadAndReleasesItAfterRejection()
    {
        try (BslWriteReviews reviews = new BslWriteReviews(Clock.systemUTC(), Runnable::run))
        {
            String large = "\u0416".repeat(2_000_000);
            String first = reviews.register(write("a.bsl", large), "x", (id, value) -> null).snapshot().reviewId();
            String second = reviews.register(write("b.bsl", large), "x", (id, value) -> null).snapshot().reviewId();
            assertNotNull(reviews.register(write("c.bsl", large), "x", (id, value) -> null).errorJson());
            reviews.reject(first);
            reviews.reject(second);
            assertNotNull(reviews.register(write("c.bsl", large), "x", (id, value) -> null).snapshot());
        }
    }

    @Test
    public void repeatApplyAndCloseHaveOneWinner()
    {
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        try (BslWriteReviews reviews = new BslWriteReviews(Clock.systemUTC(), work::add))
        {
            String id = reviews.register(write("a.bsl", "new"), "x", (key, value) -> {
                assertTrue(reviews.tryEnterMutation(key));
                assertFalse(reviews.tryEnterMutation(key));
                calls.incrementAndGet();
                return new BslWriteReviews.Outcome(BslWriteReviews.State.APPLIED, true, "written", null);
            }).snapshot().reviewId();
            reviews.apply(id);
            reviews.apply(id);
            reviews.reject(id);
            assertEquals(1, work.size());
            work.remove().run();
            assertEquals(1, calls.get());
            assertEquals(BslWriteReviews.State.APPLIED, reviews.get(id).status());
        }
    }

    @Test
    public void serverStopRevokesAwaitingProposalAndQueuedApplications()
    {
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        try (BslWriteReviews reviews = new BslWriteReviews(Clock.systemUTC(), work::add))
        {
            String id = reviews.register(write("a.bsl", "new"), "x", (key, value) -> {
                calls.incrementAndGet(); return null;
            }).snapshot().reviewId();
            reviews.apply(id);
            reviews.cancelPending("server-stopped");
            work.remove().run();
            assertEquals(BslWriteReviews.State.CANCELLED, reviews.get(id).status());
            assertEquals(0, calls.get());
            reviews.beginGeneration();
            String next = reviews.register(write("a.bsl", "new"), "x", (key, value) -> null).snapshot().reviewId();
            assertNotEquals(id, next);
            reviews.apply(id);
            assertEquals(BslWriteReviews.State.CANCELLED, reviews.get(id).status());
        }
    }

    @Test
    public void cancellationCannotRelabelEnteredMutation()
    {
        try (BslWriteReviews reviews = new BslWriteReviews(Clock.systemUTC(), Runnable::run))
        {
            String id = reviews.register(write("a.bsl", "new"), "x", (key, value) -> {
                assertTrue(reviews.tryEnterMutation(key));
                reviews.cancelPending("stop");
                return new BslWriteReviews.Outcome(BslWriteReviews.State.FAILED, true, null, "export failed");
            }).snapshot().reviewId();
            reviews.apply(id);
            assertEquals(BslWriteReviews.State.FAILED, reviews.get(id).status());
            assertEquals(Boolean.TRUE, reviews.get(id).mutationCommitted());
        }
    }

    @Test
    public void projectCloseInvalidatesTokenEvenAtTheSameLocation()
    {
        try (BslWriteReviews reviews = new BslWriteReviews(Clock.systemUTC(), Runnable::run))
        {
            long token = reviews.projectToken("Project");
            String id = reviews.register(write("a.bsl", "new"), "x", (key, value) -> null).snapshot().reviewId();
            reviews.invalidateProject("Project");
            assertNotEquals(token, reviews.projectToken("Project"));
            reviews.apply(id);
            assertEquals(BslWriteReviews.State.CANCELLED, reviews.get(id).status());
        }
    }
    @Test
    public void concurrentApplyAndRejectScheduleAtMostOneCommit() throws Exception
    {
        for (int attempt = 0; attempt < 40; attempt++)
        {
            List<Runnable> work = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger writes = new AtomicInteger();
            try (BslWriteReviews reviews = new BslWriteReviews(Clock.systemUTC(), work::add))
            {
                String id = reviews.register(write("a.bsl", "secret-proposed-source"), "x", (key, value) -> {
                    if (reviews.tryEnterMutation(key)) writes.incrementAndGet();
                    return new BslWriteReviews.Outcome(BslWriteReviews.State.APPLIED, true, "written", null);
                }).snapshot().reviewId();
                assertFalse(reviews.get(id).toJson().contains("secret-proposed-source"));
                assertTrue(work.isEmpty());
                var start = new java.util.concurrent.CountDownLatch(1);
                Thread accept = new Thread(() -> { await(start); reviews.apply(id); reviews.apply(id); });
                Thread reject = new Thread(() -> { await(start); reviews.reject(id); });
                accept.start(); reject.start(); start.countDown();
                accept.join(5000); reject.join(5000);
                assertFalse(accept.isAlive()); assertFalse(reject.isAlive());
                assertTrue(work.size() <= 1);
                for (Runnable run : work) run.run();
                assertEquals(reviews.get(id).status() == BslWriteReviews.State.APPLIED ? 1 : 0, writes.get());
            }
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch)
    {
        try { latch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

}
