/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;

/** Withdraw a pending BSL review without applying or saving source. */
public final class CancelWriteReviewTool implements IMcpTool
{
    public static final String NAME = "cancel_write_review";
    @Override public String getName() { return NAME; }
    @Override public String getDescription()
    {
        return "Withdraw an awaiting BSL write review by reviewId. Applying or completed reviews keep their actual outcome. "
            + "Parameters and examples: get_tool_guide('cancel_write_review').";
    }
    @Override public ResponseType getResponseType() { return ResponseType.JSON; }
    @Override public ToolAnnotations getAnnotations()
    { return new ToolAnnotations("Cancel BSL write review", false, true, true, false); }
    @Override public String getInputSchema()
    {
        return JsonSchemaBuilder.object().stringProperty("reviewId", "ID returned by write_module_source.", true).build();
    }
    @Override public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the review was found", true)
            .stringProperty("reviewId", "Review identity")
            .stringProperty("status", "awaitingApproval, applying, applied, rejected, stale, expired, cancelled, or failed")
            .stringProperty("projectName", "EDT project name").stringProperty("modulePath", "Canonical source-relative path")
            .booleanProperty("applied", "Whether source is known to have been written")
            .booleanProperty("mutationCommitted", "Known source mutation outcome")
            .booleanProperty("mutationOutcomeUnknown", "Reread source before retrying")
            .stringProperty("createdAt", "UTC creation time").stringProperty("expiresAt", "UTC decision deadline")
            .stringProperty("reason", "Reason for the terminal decision")
            .stringProperty("completedAt", "UTC completion time").stringProperty("resultMarkdown", "Original completed write response")
            .build();
    }
    @Override public String execute(Map<String, String> params)
    {
        String missing = JsonUtils.requireArgument(params, "reviewId");
        if (missing != null) return missing;
        String id = JsonUtils.extractStringArgument(params, "reviewId");
        if (id.isBlank()) return ToolResult.error("reviewId must not be blank. Use the ID returned by write_module_source.").toJson();
        var snapshot = BslWriteReviews.shared().cancel(id);
        return snapshot == null
            ? ToolResult.error("Unknown or expired reviewId '" + id + "'. IDs are in memory and may be evicted or lost on restart. Read the module before submitting another write.").toJson()
            : snapshot.toJson();
    }
}
