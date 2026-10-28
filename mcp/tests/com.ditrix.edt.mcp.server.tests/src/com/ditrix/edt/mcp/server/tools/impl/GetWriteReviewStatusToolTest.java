package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import com.ditrix.edt.mcp.server.preferences.ToolPreset;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;
import com.ditrix.edt.mcp.server.utils.PreparedBslWrite;
import com.google.gson.JsonParser;

public class GetWriteReviewStatusToolTest
{
    @After public void cleanup() { BslWriteReviews.shutdownShared(); }
    @Test public void rejectsMissingBlankAndUnknownIdsWithActionableErrors()
    {
        var tool = new GetWriteReviewStatusTool();
        for (Map<String,String> params : java.util.List.of(Map.<String,String>of(), Map.of("reviewId", " "), Map.of("reviewId", "unknown")))
        {
            var result = JsonParser.parseString(tool.execute(params)).getAsJsonObject();
            assertFalse(result.toString(), result.get("success").getAsBoolean());
            assertTrue(result.toString(), result.get("error").getAsString().length() > 10);
        }
        assertTrue(tool.getAnnotations().getReadOnlyHint());
        assertFalse(ToolPreset.ANALYSIS_ONLY.getDisabledTools().contains(tool.getName()));
        assertFalse(ToolPreset.CODE_REVIEW.getDisabledTools().contains(tool.getName()));
    }
    @Test public void pollingReturnsIdentityButDoesNotAcceptOrExposeSource()
    {
        var reviews = BslWriteReviews.shared();
        var write = new PreparedBslWrite("p", "file:/p", 0, "a.bsl", "file:/p/a.bsl", true,
            "hash", "secret-old", "secret-new", new byte[] { 1 }, 1, 1, "append", false, null);
        String id = reviews.register(write, "x", (key,value) -> { fail("Polling must not apply"); return null; }).snapshot().reviewId();
        String json = new GetWriteReviewStatusTool().execute(Map.of("reviewId", id));
        var result = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(id, result.get("reviewId").getAsString());
        assertEquals("awaitingApproval", result.get("status").getAsString());
        assertFalse(result.get("applied").getAsBoolean());
        assertFalse(json.contains("secret"));
    }
}
