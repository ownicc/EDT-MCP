/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Bounded workspace-local write receipts. No source arguments or executable work are persisted. */
public final class BslWriteRequests
{
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int MAX_RECEIPTS = 1000;
    private static final int MAX_BYTES = 65_536;
    private static BslWriteRequests shared;
    private final Path directory;
    private final Clock clock;
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();

    private static final class Receipt
    {
        final String requestId;
        final String digest;
        final Instant retainedUntil;
        JsonObject response;
        boolean complete;
        boolean reviewObserved;
        Receipt(String requestId, String digest, Instant retainedUntil, JsonObject response, boolean complete)
        {
            this.requestId = requestId;
            this.digest = digest;
            this.retainedUntil = retainedUntil;
            this.response = response;
            this.complete = complete;
        }
    }

    public BslWriteRequests(Path directory) throws IOException { this(directory, Clock.systemUTC()); }

    BslWriteRequests(Path directory, Clock clock) throws IOException
    {
        this.directory = directory;
        this.clock = clock;
        Files.createDirectories(directory);
        try (var paths = Files.list(directory))
        {
            var files = paths.filter(p -> p.getFileName().toString().endsWith(".json")).limit(MAX_RECEIPTS + 1L).toList();
            if (files.size() > MAX_RECEIPTS) throw new IOException("Too many BSL write receipt files");
            for (Path path : files)
            {
                Receipt receipt = read(path);
                receipts.put(receipt.requestId, receipt);
                if (!receipt.complete)
                {
                    JsonObject unknown = JsonParser.parseString(ToolResult.errorWithUnknownMutationOutcome(
                        "The previous write was interrupted or its review did not survive restart. Read the module before deciding what to do; never resubmit this requestId.")
                        .put("status", "unknown").put("recovery", "interrupted").toJson()).getAsJsonObject();
                    if (receipt.response.has("reviewId")) unknown.add("reviewId", receipt.response.get("reviewId"));
                    receipt.response = envelope(receipt, unknown);
                    receipt.complete = true;
                    persist(receipt);
                }
            }
        }
    }

    public static synchronized BslWriteRequests shared() throws IOException
    {
        if (shared == null)
        {
            Activator plugin = Activator.getDefault();
            if (plugin == null) throw new IOException("Workspace receipt storage is unavailable. Start the EDT MCP plugin before using requestId.");
            try { shared = new BslWriteRequests(plugin.getStateLocation().append("bsl-write-requests").toFile().toPath()); }
            catch (RuntimeException e) { throw new IOException("Workspace receipt storage is unavailable", e); }
        }
        return shared;
    }

    /** Admission is flushed before work starts. No store lock is held while calling the writer. */
    public String execute(String requestId, String digest, Supplier<String> operation)
    {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128)
            return ToolResult.error("requestId must contain 1 to 128 characters and must never be reused for another write.")
                .put("code", "invalidRequestId").toJson();
        Receipt receipt;
        synchronized (this)
        {
            receipt = receipts.get(requestId);
            if (receipt != null)
            {
                if (!clock.instant().isBefore(receipt.retainedUntil)) return expired(receipt);
                if (!receipt.digest.equals(digest)) return ToolResult.error(
                    "requestId '" + requestId + "' was already used with different write arguments. Use its original arguments to replay; use a new ID only for a new operation.")
                    .put("code", "requestIdConflict").put("requestId", requestId).toJson();
                return serialize(receipt);
            }
            try
            {
                pruneExpired();
                if (receipts.size() >= MAX_RECEIPTS) return ToolResult.error(
                    "BSL write receipt capacity reached. Wait for retainedUntil; do not recycle a requestId.")
                    .put("code", "requestCapacity").toJson();
                JsonObject pending = JsonParser.parseString(ToolResult.success().put("status", "inProgress")
                    .put("mutationOutcomeUnknown", true).toJson()).getAsJsonObject();
                receipt = new Receipt(requestId, digest, clock.instant().plus(RETENTION), pending, false);
                receipt.response = envelope(receipt, pending);
                persist(receipt);
                receipts.put(requestId, receipt);
            }
            catch (IOException e)
            {
                return ToolResult.error("Cannot persist requestId admission; no write was started. Check workspace storage and retry: " + e.getMessage())
                    .put("requestId", requestId).put("mutationCommitted", false).toJson();
            }
        }
        String result;
        try { result = operation.get(); }
        catch (RuntimeException e)
        {
            result = ToolResult.errorWithUnknownMutationOutcome(
                "Write execution did not report a final outcome. Read the module before deciding what to do.")
                .put("status", "unknown").toJson();
        }
        synchronized (this)
        {
            // Synchronous review observers may already have advanced beyond the writer's returned snapshot.
            if (!receipt.reviewObserved)
            {
                receipt.response = envelope(receipt, resultObject(result));
                receipt.complete = true;
                saveOutcome(receipt);
            }
            return serialize(receipt);
        }
    }

    /** Subscribe outside the store monitor: the registry invokes listeners under its own monitor. */
    public void observeReview(String requestId, BslWriteReviews reviews, String reviewId)
    {
        reviews.subscribe(reviewId, snapshot -> {
            synchronized (BslWriteRequests.this)
            {
                Receipt receipt = receipts.get(requestId);
                if (receipt == null) return;
                receipt.reviewObserved = true;
                receipt.response = envelope(receipt, JsonParser.parseString(snapshot.toJson()).getAsJsonObject());
                receipt.complete = snapshot.status().terminal();
                saveOutcome(receipt);
            }
        });
    }

    public synchronized String getByRequestId(String requestId)
    {
        Receipt receipt = receipts.get(requestId);
        return receipt == null ? null : clock.instant().isBefore(receipt.retainedUntil) ? serialize(receipt) : expired(receipt);
    }

    public synchronized String getByReviewId(String reviewId)
    {
        for (Receipt receipt : receipts.values())
            if (receipt.response.has("reviewId") && reviewId.equals(receipt.response.get("reviewId").getAsString()))
                return clock.instant().isBefore(receipt.retainedUntil) ? serialize(receipt) : expired(receipt);
        return null;
    }

    private static String expired(Receipt receipt)
    {
        return ToolResult.error("requestId retention expired. Read the module and reconcile its outcome; never reuse this ID.")
            .put("code", "requestIdExpired").put("requestId", receipt.requestId)
            .put("retainedUntil", receipt.retainedUntil.toString()).toJson();
    }

    private static JsonObject resultObject(String result)
    {
        if (result != null && result.stripLeading().startsWith("{"))
        {
            try
            {
                JsonObject object = JsonParser.parseString(result).getAsJsonObject();
                if (!object.has("status")) object.addProperty("status", ToolResult.isErrorPayload(result) ? "failed" : "completed");
                if (!object.has("mutationCommitted") && !object.has("mutationOutcomeUnknown")) object.addProperty("mutationCommitted", false);
                return object;
            }
            catch (RuntimeException ignored) { /* Opaque writer result must not reopen admission. */ }
        }
        if (result == null) return JsonParser.parseString(ToolResult.errorWithUnknownMutationOutcome(
            "Write returned no outcome. Read the module before deciding what to do.").put("status", "unknown").toJson()).getAsJsonObject();
        return JsonParser.parseString(ToolResult.success().put("status", "applied").put("mutationCommitted", true)
            .put("applied", true).put("resultMarkdown", result).toJson()).getAsJsonObject();
    }

    private static JsonObject envelope(Receipt receipt, JsonObject object)
    {
        object.addProperty("requestId", receipt.requestId);
        object.addProperty("retainedUntil", receipt.retainedUntil.toString());
        object.addProperty("nextTool", "get_write_review_status");
        return object;
    }

    private static String serialize(Receipt receipt) { return GsonProvider.toJson(receipt.response); }

    private void saveOutcome(Receipt receipt)
    {
        try { persist(receipt); }
        catch (IOException e)
        {
            // Keep the in-memory outcome and the prior durable fence; never retry the operation.
            receipt.response.addProperty("receiptPersistenceFailed", true);
            receipt.response.addProperty("receiptWarning", "Could not persist the latest outcome. Restart recovery may report unknown; read the module before another write.");
        }
    }

    private void pruneExpired() throws IOException
    {
        var iterator = receipts.values().iterator();
        while (iterator.hasNext())
        {
            Receipt receipt = iterator.next();
            if (!clock.instant().isBefore(receipt.retainedUntil))
            {
                Files.deleteIfExists(path(receipt.requestId));
                iterator.remove();
            }
        }
    }

    private Path path(String requestId)
    {
        return directory.resolve(PreparedBslWrite.sha256(requestId.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    private void persist(Receipt receipt) throws IOException
    {
        JsonObject record = record(receipt);
        byte[] bytes = GsonProvider.toJson(record).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES)
        {
            JsonObject summary = receipt.response.deepCopy();
            summary.remove("resultMarkdown");
            summary.remove("errorDetails");
            summary.remove("error");
            summary.addProperty("resultTruncated", true);
            if (!summary.get("success").getAsBoolean()) summary.addProperty("error", "Write failed; detailed result exceeded receipt size. Read the module before deciding what to do.");
            receipt.response = summary;
            bytes = GsonProvider.toJson(record(receipt)).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Receipt exceeds 64 KiB");
        }
        Path temporary = Files.createTempFile(directory, "receipt-", ".tmp");
        try
        {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path(receipt.requestId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temporary); }
    }

    private static JsonObject record(Receipt receipt)
    {
        JsonObject record = new JsonObject();
        record.addProperty("version", 1);
        record.addProperty("requestId", receipt.requestId);
        record.addProperty("digest", receipt.digest);
        record.addProperty("retainedUntil", receipt.retainedUntil.toString());
        record.addProperty("complete", receipt.complete);
        record.add("response", receipt.response);
        return record;
    }

    private Receipt read(Path path) throws IOException
    {
        try (var input = Files.newInputStream(path))
        {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Oversized BSL receipt: " + path.getFileName());
            JsonObject record = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = record.get("requestId").getAsString();
            String digest = record.get("digest").getAsString();
            if (record.get("version").getAsInt() != 1 || id.isBlank() || id.length() > 128
                || !digest.matches("[a-f0-9]{64}") || !path(id).getFileName().equals(path.getFileName()))
                throw new IOException("Invalid BSL receipt: " + path.getFileName());
            JsonObject response = record.getAsJsonObject("response");
            if (!id.equals(response.get("requestId").getAsString()) || !response.has("status") || !response.has("success"))
                throw new IOException("Invalid BSL receipt response");
            return new Receipt(id, digest, Instant.parse(record.get("retainedUntil").getAsString()),
                response, record.get("complete").getAsBoolean());
        }
        catch (RuntimeException e) { throw new IOException("Cannot read BSL receipt " + path.getFileName(), e); }
    }
}
