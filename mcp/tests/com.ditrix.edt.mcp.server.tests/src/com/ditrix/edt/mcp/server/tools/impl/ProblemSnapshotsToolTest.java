/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Diagnostic;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Scope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ProblemSnapshotsToolTest
{
    @Test
    public void contractDeclaresConditionalArgumentsAndStructuredResults()
    {
        var tool = new ProblemSnapshotsTool();
        assertEquals("problem_snapshots", tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertEquals(Boolean.TRUE, tool.getAnnotations().getReadOnlyHint());
        assertEquals(Boolean.FALSE, tool.getAnnotations().getIdempotentHint());
        assertTrue(tool.getDescription().contains("get_tool_guide('problem_snapshots')"));
        JsonObject input = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals(1, input.getAsJsonArray("required").size());
        assertEquals("action", input.getAsJsonArray("required").get(0).getAsString());
        assertEquals(9, input.getAsJsonObject("properties").size());
        assertTrue(JsonParser.parseString(tool.getOutputSchema()).getAsJsonObject()
            .getAsJsonObject("properties").has("counts"));
    }

    @Test
    public void invalidCallsRefuseBeforeWorkspaceAccess()
    {
        var tool = new ProblemSnapshotsTool(new DiagnosticSnapshots());
        error(tool, Map.of(), "action", "capture");
        error(tool, Map.of("action", "bad-action"), "bad-action", "compare");
        error(tool, Map.of("action", "capture"), "projectName", "list_projects");
        error(tool, Map.of("action", "capture", "projectName", "P", "severity", "wrong"), "wrong", "MAJOR");
        error(tool, Map.of("action", "capture", "projectName", "P", "modulePaths", "src/"), "src/", "modulePaths");
        error(tool, Map.of("action", "compare"), "baselineId", "capture");
        error(tool, Map.of("action", "compare", "baselineId", "x"), "snapshotId", "capture");
        error(tool, Map.of("action", "compare", "baselineId", "x", "snapshotId", "y", "offset", "1.5"), "1.5", "integer");
        error(tool, Map.of("action", "compare", "baselineId", "x", "snapshotId", "y", "offset", "-1"), "-1", "0");
        error(tool, Map.of("action", "compare", "baselineId", "x", "snapshotId", "y", "limit", "huge"), "huge", "integer");
        error(tool, Map.of("action", "capture", "projectName", "P", "baselineId", "x"), "baselineId", "compare");
        error(tool, Map.of("action", "compare", "baselineId", "x", "snapshotId", "y", "severity", "MAJOR"), "severity", "capture");
    }

    @Test
    public void captureAndCompareReturnRealCountsAndBeforeAfterLines()
    {
        var store = new DiagnosticSnapshots();
        Scope scope = new Scope("P", null, null, List.of());
        Diagnostic before = new Diagnostic("CommonModules/A/Module.bsl", "A", "check", "vendor",
            "MAJOR", "message", 3, 30, 1);
        Diagnostic after = new Diagnostic("CommonModules/A/Module.bsl", "A", "check", "vendor",
            "MAJOR", "message", 9, 90, 1);
        int[] calls = {0};
        var tool = new ProblemSnapshotsTool(store, requested -> {
        assertEquals(scope, requested);
            return List.of(calls[0]++ == 0 ? before : after);
        }, project -> assertEquals("P", project));
        var first = json(tool.execute(Map.of("action", "capture", "projectName", "P")));
        var second = json(tool.execute(Map.of("action", "capture", "projectName", "P")));
        assertEquals(1, first.get("problemCount").getAsInt());
        assertEquals("P", first.getAsJsonObject("scope").get("projectName").getAsString());
        assertTrue(first.has("capturedAt"));
        var result = json(tool.execute(Map.of("action", "compare", "baselineId",
            first.get("snapshotId").getAsString(), "snapshotId", second.get("snapshotId").getAsString())));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals(1, result.getAsJsonObject("counts").get("remaining").getAsInt());
        assertEquals("signatureCounts", result.get("matchingBasis").getAsString());
        JsonObject row = result.getAsJsonArray("problems").get(0).getAsJsonObject();
        assertEquals(3, row.get("beforeLine").getAsInt());
        assertEquals(9, row.get("afterLine").getAsInt());
        assertEquals(1, result.get("returned").getAsInt());
        assertFalse(result.get("hasMore").getAsBoolean());
    }

    @Test
    public void captureFailureDoesNotReplaceAnEarlierSnapshot()
    {
        var store = new DiagnosticSnapshots();
        var first = store.capture(new Scope("P", null, null, List.of()), List.of());
        var tool = new ProblemSnapshotsTool(store, scope -> {
            throw new IllegalStateException("incomplete capture; retry revalidate_objects");
        }, project -> {});
        error(tool, Map.of("action", "capture", "projectName", "P"), "incomplete", "revalidate_objects");
        assertEquals(first, store.require(first.id()));
    }

    private static JsonObject json(String value)
    {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static void error(ProblemSnapshotsTool tool, Map<String, String> params, String... expected)
    {
        JsonObject result = json(tool.execute(params));
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        for (String text : expected)
        {
        assertTrue(result.toString(), result.get("error").getAsString().contains(text));
        }
    }
}
