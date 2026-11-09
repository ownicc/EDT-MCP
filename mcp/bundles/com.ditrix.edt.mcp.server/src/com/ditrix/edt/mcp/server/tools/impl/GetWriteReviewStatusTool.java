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
import com.ditrix.edt.mcp.server.utils.BslWriteRequests;

/** Immediate read-only polling for human BSL write decisions. */
public final class GetWriteReviewStatusTool implements IMcpTool
{
    private final BslWriteRequests requestStore;
    public GetWriteReviewStatusTool() { this(null); }
    GetWriteReviewStatusTool(BslWriteRequests requestStore) { this.requestStore = requestStore; }

    public static final String NAME = "get_write_review_status";
    @Override public String getName() { return NAME; }
    @Override public String getDescription()
    {
        return "Get a BSL write review by reviewId or a durable operation receipt by requestId. Only the human can apply a review in EDT. "
            + "Parameters and examples: get_tool_guide('get_write_review_status').";
    }
    @Override public ResponseType getResponseType() { return ResponseType.JSON; }
    @Override public ToolAnnotations getAnnotations()
    { return new ToolAnnotations("BSL write review status", true, false, true, false); }
    @Override public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("reviewId", "Review ID returned by write_module_source. Pass exactly one of reviewId or requestId.")
            .stringProperty("requestId", "Operation ID supplied to write_module_source, including recovery after restart.").build();
    }
    @Override public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the lookup succeeded", true)
            .stringProperty("reviewId", "Review identity")
            .stringProperty("requestId", "Durable write operation identity")
            .stringProperty("retainedUntil", "UTC receipt retention deadline")
            .stringProperty("recovery", "interrupted when a nonterminal operation was recovered")
            .booleanProperty("receiptPersistenceFailed", "Latest result could not be persisted; reconcile before another write")
            .booleanProperty("resultTruncated", "Result details exceeded the bounded receipt size")
            .stringProperty("status", "awaitingApproval, applying, applied, rejected, stale, expired, cancelled, failed, inProgress, completed, or unknown")
            .stringProperty("projectName", "EDT project name").stringProperty("modulePath", "Canonical source-relative path")
            .booleanProperty("applied", "Whether source is known to have been written")
            .booleanProperty("mutationCommitted", "Known source mutation outcome")
            .booleanProperty("mutationOutcomeUnknown", "Reread source before retrying")
            .stringProperty("createdAt", "UTC creation time").stringProperty("expiresAt", "UTC decision deadline")
            .stringProperty("completedAt", "UTC completion time").stringProperty("resultMarkdown", "Original completed write response")
            .build();
    }
    @Override public String execute(Map<String, String> params)
    {
        String reviewId = JsonUtils.extractStringArgument(params, "reviewId");
        String requestId = JsonUtils.extractStringArgument(params, "requestId");
        if ((reviewId == null) == (requestId == null))
            return ToolResult.error("Exactly one of reviewId or requestId is required. Use an ID from write_module_source.").toJson();
        String id = reviewId != null ? reviewId : requestId;
        if (id.isBlank()) return ToolResult.error((reviewId != null ? "reviewId" : "requestId")
            + " must not be blank. Use the ID from write_module_source.").toJson();
        if (reviewId != null)
        {
            var snapshot = BslWriteReviews.shared().get(reviewId);
            if (snapshot != null) return snapshot.toJson();
        }
        try
        {
            BslWriteRequests store = requestStore != null ? requestStore : BslWriteRequests.shared();
            String recovered = reviewId != null ? store.getByReviewId(reviewId) : store.getByRequestId(requestId);
            if (recovered != null) return recovered;
        }
        catch (java.io.IOException e)
        {
            if (requestId != null) return ToolResult.error(
                "Cannot read workspace requestId receipts. Check workspace storage and read the module before submitting another write: " + e.getMessage()).toJson();
        }
        return ToolResult.error("Unknown or expired " + (reviewId != null ? "reviewId" : "requestId") + " '" + id
            + "'. Reviews may be lost on restart; receipts have bounded retention. Read the module before submitting another write.").toJson();
    }
}
