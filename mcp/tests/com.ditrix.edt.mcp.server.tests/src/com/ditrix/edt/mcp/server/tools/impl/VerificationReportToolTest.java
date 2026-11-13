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

import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Diagnostic;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Scope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class VerificationReportToolTest
{
    private static final Scope SCOPE = new Scope("P", null, null, List.of());

    @Test
    public void sameCountReplacementIsARegressionAndDetailsAreExplicitlyTruncated()
    {
        var store = new DiagnosticSnapshots();
        var baseline = store.capture(SCOPE, List.of(problem("old"), problem("remaining")));
        var tool = tool(store, List.of(problem("new"), problem("remaining")), ValidationState.COMPLETE);
        var report = json(tool.execute(Map.of("baselineId", baseline.id(), "limit", "1")));
        assertTrue(report.toString(), report.get("success").getAsBoolean());
        assertEquals("regression", report.get("verdict").getAsString());
        var diagnostics = report.getAsJsonObject("diagnostics");
        assertEquals(1, diagnostics.getAsJsonObject("counts").get("new").getAsInt());
        assertEquals(1, diagnostics.getAsJsonObject("counts").get("fixed").getAsInt());
        assertEquals(1, diagnostics.getAsJsonObject("counts").get("remaining").getAsInt());
        assertTrue(diagnostics.get("truncated").getAsBoolean());
        assertEquals(1, diagnostics.getAsJsonArray("problems").size());
        assertEquals("notSelected", report.getAsJsonObject("tests").get("state").getAsString());
        assertTrue(report.has("baselineCapturedAt"));
        assertTrue(report.has("capturedAt"));
        assertTrue(report.get("summary").getAsString().contains("1"));
    }

    @Test
    public void remainingProblemsDoNotBecomeAnErrorFreeClaim()
    {
        var store = new DiagnosticSnapshots();
        var baseline = store.capture(SCOPE, List.of(problem("existing")));
        var report = json(tool(store, baseline.problems(), ValidationState.COMPLETE)
            .execute(Map.of("baselineId", baseline.id())));
        assertEquals("noNewProblems", report.get("verdict").getAsString());
        assertEquals(1, report.getAsJsonObject("diagnostics").get("problemCount").getAsInt());
        assertFalse(report.getAsJsonObject("diagnostics").get("clean").getAsBoolean());
    }

    @Test
    public void pendingAndUnobservableValidationNeverCaptureOrReportClean()
    {
        for (var state : List.of(ValidationState.PENDING, ValidationState.UNOBSERVABLE))
        {
            var store = new DiagnosticSnapshots();
            var baseline = store.capture(SCOPE, List.of());
            var tool = new VerificationReportTool(store, scope -> {
                fail("Do not capture before confirmed validation"); return List.of();
            }, (project, timeout) -> state, project -> {});
            var report = json(tool.execute(Map.of("baselineId", baseline.id())));
            assertEquals("incomplete", report.get("verdict").getAsString());
            assertEquals(state.name().toLowerCase(java.util.Locale.ROOT),
                report.getAsJsonObject("validation").get("state").getAsString());
            assertFalse(report.getAsJsonObject("diagnostics").has("counts"));
        }
    }

    @Test
    public void captureFailureIsIncompleteAndDoesNotInventZeroCounts()
    {
        var store = new DiagnosticSnapshots();
        var baseline = store.capture(SCOPE, List.of());
        var tool = new VerificationReportTool(store, scope -> {
            throw new IllegalStateException("marker service unavailable");
        }, (project, timeout) -> ValidationState.COMPLETE, project -> {});
        var report = json(tool.execute(Map.of("baselineId", baseline.id())));
        assertEquals("incomplete", report.get("verdict").getAsString());
        assertFalse(report.getAsJsonObject("diagnostics").has("counts"));
        assertTrue(report.getAsJsonObject("diagnostics").get("reason").getAsString().contains("marker service"));
    }

    @Test
    public void admittedBaselineSurvivesRetentionEvictionDuringCapture()
    {
        var store = new DiagnosticSnapshots();
        var baseline = store.capture(SCOPE, List.of(problem("before")));
        var tool = new VerificationReportTool(store, scope -> {
            assertEquals(SCOPE, scope);
            for (int i = 0; i < DiagnosticSnapshots.MAX_SNAPSHOTS; i++) store.capture(scope, List.of());
            return List.of();
        }, (project, timeout) -> ValidationState.COMPLETE, project -> {});
        var report = json(tool.execute(Map.of("baselineId", baseline.id())));
        assertTrue(report.toString(), report.get("success").getAsBoolean());
        assertEquals(1, report.getAsJsonObject("diagnostics").getAsJsonObject("counts").get("fixed").getAsInt());
    }

    @Test
    public void invalidRequestsFailBeforeProjectOrValidationAccess()
    {
        var tool = new VerificationReportTool(new DiagnosticSnapshots(), scope -> {
            fail("capture"); return List.of();
        }, (project, timeout) -> { fail("wait"); return null; }, project -> fail("project"));
        for (var params : List.of(Map.<String, String>of(), Map.of("baselineId", "missing"),
            Map.of("baselineId", "x", "waitSeconds", "16"),
            Map.of("baselineId", "x", "limit", "1.5"),
            Map.of("baselineId", "x", "testReports", "relative.xml")))
        {
            assertFalse(json(tool.execute(params)).get("success").getAsBoolean());
        }
    }

    private static VerificationReportTool tool(DiagnosticSnapshots store, List<Diagnostic> after, ValidationState state)
    {
        return new VerificationReportTool(store, scope -> after, (project, timeout) -> state, project -> {});
    }

    private static Diagnostic problem(String message)
    {
        return new Diagnostic("CommonModules/A/Module.bsl", "A", "check", "vendor", "CRITICAL", message, 3, 30, 1);
    }

    private static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
