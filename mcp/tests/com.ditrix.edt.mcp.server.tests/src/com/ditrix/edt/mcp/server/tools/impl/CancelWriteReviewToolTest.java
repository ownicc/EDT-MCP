package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;
import com.ditrix.edt.mcp.server.utils.PreparedBslWrite;
import com.google.gson.JsonParser;

public class CancelWriteReviewToolTest
{
    @After public void cleanup() { BslWriteReviews.shutdownShared(); }

    @Test public void contractAndInvalidIdsAreActionable()
    {
        var tool = new CancelWriteReviewTool();
        assertEquals("cancel_write_review", tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.getDescription().contains("get_tool_guide('cancel_write_review')"));
        assertFalse(tool.getAnnotations().getReadOnlyHint());
        assertTrue(tool.getAnnotations().getIdempotentHint());
        var schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals("reviewId", schema.getAsJsonArray("required").get(0).getAsString());
        for (String key : java.util.List.of("reviewId", "status", "mutationCommitted", "reason", "completedAt"))
            assertTrue(tool.getOutputSchema(), JsonParser.parseString(tool.getOutputSchema())
                .getAsJsonObject().getAsJsonObject("properties").has(key));
        for (Map<String,String> params : java.util.List.of(Map.<String,String>of(), Map.of("reviewId", " "), Map.of("reviewId", "unknown")))
        {
            var result = JsonParser.parseString(tool.execute(params)).getAsJsonObject();
            assertFalse(result.toString(), result.get("success").getAsBoolean());
            assertTrue(result.toString(), result.get("error").getAsString().contains("reviewId"));
        }
    }

    @Test public void withdrawalReturnsRepeatableSnapshotWithoutSourceOrCommit()
    {
        var reviews = BslWriteReviews.shared();
        var write = new PreparedBslWrite("p", "file:/p", 0, "a.bsl", "file:/p/a.bsl", true,
            "hash", "secret-old", "secret-new", new byte[] { 1 }, 1, 1, "append", false, null);
        String id = reviews.register(write, "x", (key,value) -> { fail("Withdrawal must not apply"); return null; }).snapshot().reviewId();
        var tool = new CancelWriteReviewTool();
        String json = tool.execute(Map.of("reviewId", id));
        var result = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(id, result.get("reviewId").getAsString());
        assertEquals("cancelled", result.get("status").getAsString());
        assertFalse(result.get("applied").getAsBoolean());
        assertFalse(json.contains("secret"));
        assertEquals(json, tool.execute(Map.of("reviewId", id)));
        assertEquals(json, new GetWriteReviewStatusTool().execute(Map.of("reviewId", id)));
    }
}
