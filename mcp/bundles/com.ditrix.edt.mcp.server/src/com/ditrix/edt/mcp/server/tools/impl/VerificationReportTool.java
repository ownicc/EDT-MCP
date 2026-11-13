/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshotCapture;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Diagnostic;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Scope;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.VerificationTestReports;
import com.ditrix.edt.mcp.server.utils.VerificationValidation;

/** One bounded evidence report; it never saves sources, launches tests or updates an infobase. */
public class VerificationReportTool implements IMcpTool
{
    public static final String NAME = "verification_report"; //$NON-NLS-1$
    private final DiagnosticSnapshots snapshots;
    private final Function<Scope, List<Diagnostic>> capture;
    private final BiFunction<String, Long, ValidationState> validation;
    private final Consumer<String> requireOpenProject;

    public VerificationReportTool()
    {
        this(DiagnosticSnapshots.shared(), DiagnosticSnapshotCapture::capture,
            VerificationValidation::await, DiagnosticSnapshotCapture::requireOpenProject);
    }

    VerificationReportTool(DiagnosticSnapshots snapshots, Function<Scope, List<Diagnostic>> capture,
        BiFunction<String, Long, ValidationState> validation, Consumer<String> requireOpenProject)
    {
        this.snapshots = snapshots;
        this.capture = capture;
        this.validation = validation;
        this.requireOpenProject = requireOpenProject;
    }

    @Override public String getName() { return NAME; }
    @Override public ResponseType getResponseType() { return ResponseType.JSON; }
    @Override public ToolAnnotations getAnnotations()
    {
        return new ToolAnnotations(null, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
    }

    @Override public String getDescription()
    {
        return "Verify changes against a pre-edit diagnostic baseline: observe validation, compare " //$NON-NLS-1$
            + "new/fixed/remaining problems and read explicitly selected JUnit reports. No test launch " //$NON-NLS-1$
            + "or database update. Parameters: get_tool_guide('verification_report')."; //$NON-NLS-1$
    }

    @Override public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("baselineId", "snapshotId from problem_snapshots(action='capture') before editing.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("testReports", "Up to 10 explicit absolute JUnit XML paths on the EDT server. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Omit when no tests were selected. Reports older than the baseline are incomplete evidence.") //$NON-NLS-1$
            .integerProperty("waitSeconds", "Bounded validation observation budget, 1-15 seconds; default 5.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Diagnostic detail rows, 1-1000; default 100. Counts stay complete.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "The report was assembled; does not mean verification passed.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty("verdict", "Observed outcome within this scope; never a source revision guarantee.", true, //$NON-NLS-1$ //$NON-NLS-2$
                "regression", "incomplete", "noNewProblems") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .stringProperty("baselineId", "Pre-edit snapshot ID.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("baselineCapturedAt", "Baseline marker observation time (ISO-8601).", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("capturedAt", "Current marker observation time when capture succeeded.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("observedAt", "Report assembly time (ISO-8601).", true) //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("scope", "Exact normalized filters inherited from baseline.") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("validation", "state: complete, pending, unobservable; observed metadata validation only.") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("diagnostics", "Complete counts and bounded new/fixed/remaining rows, or incomplete reason.") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("tests", "Selected JUnit evidence: state, complete, totals and bounded per-report details.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("summary", "Human-readable outcome summary.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("limitations", "Observation and evidence provenance limits.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override public String execute(Map<String, String> params)
    {
        try
        {
            String baselineId = JsonUtils.extractStringArgument(params, "baselineId"); //$NON-NLS-1$
            if (baselineId == null || baselineId.isBlank())
                throw new IllegalArgumentException("baselineId is required; use problem_snapshots(action='capture') before editing."); //$NON-NLS-1$
            int seconds = integer(JsonUtils.extractStringArgument(params, "waitSeconds"), "waitSeconds", 5, 15); //$NON-NLS-1$ //$NON-NLS-2$
            int limit = integer(JsonUtils.extractStringArgument(params, "limit"), "limit", 100, 1000); //$NON-NLS-1$ //$NON-NLS-2$
            List<String> paths = JsonUtils.extractArrayArgument(params, "testReports"); //$NON-NLS-1$
            paths = paths == null ? List.of() : paths;
            VerificationTestReports.validatePaths(paths);
            var before = snapshots.require(baselineId);
            requireOpenProject.accept(before.scope().projectName());
            ValidationState state;
            String validationReason = "Only existing metadata validation is observed; no new validation is scheduled."; //$NON-NLS-1$
            try { state = validation.apply(before.scope().projectName(), seconds * 1000L); }
            catch (RuntimeException e)
            {
                state = ValidationState.UNOBSERVABLE;
                validationReason = PlatformFailures.describe(e);
            }
            if (state == null) state = ValidationState.UNOBSERVABLE;
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            int added = 0;
            diagnostics.put("complete", false); //$NON-NLS-1$
            diagnostics.put("reason", "Validation is " + state.name().toLowerCase(Locale.ROOT) //$NON-NLS-1$ //$NON-NLS-2$
                + "; finish revalidate_objects and retry verification_report."); //$NON-NLS-1$
            Instant capturedAt = null;
            if (state == ValidationState.COMPLETE)
            {
                try
                {
                    var after = snapshots.capture(before.scope(), capture.apply(before.scope()));
                    var comparison = DiagnosticSnapshots.compare(before, after, limit, 0);
                    var rows = comparison.problems().stream().map(ProblemSnapshotsTool::row).map(row -> {
                        boolean truncated = false;
                        for (var entry : row.entrySet())
                        {
                            if (entry.getValue() instanceof String text && text.length() > 2048)
                            {
                                entry.setValue(text.substring(0, 2048) + "… [truncated]"); //$NON-NLS-1$
                                truncated = true;
                            }
                        }
                        row.put("textTruncated", truncated); //$NON-NLS-1$
                        return row;
                    }).toList();
                    capturedAt = after.capturedAt();
                    added = comparison.added();
                    diagnostics.clear();
                    diagnostics.put("complete", true); //$NON-NLS-1$
                    diagnostics.put("snapshotId", after.id()); //$NON-NLS-1$
                    diagnostics.put("problemCount", after.problems().size()); //$NON-NLS-1$
                    diagnostics.put("clean", after.problems().isEmpty()); //$NON-NLS-1$
                    diagnostics.put("counts", comparison.counts()); //$NON-NLS-1$
                    diagnostics.put("matchingBasis", "signatureCounts"); //$NON-NLS-1$ //$NON-NLS-2$
                    diagnostics.put("total", comparison.total()); //$NON-NLS-1$
                    diagnostics.put("limit", comparison.limit()); //$NON-NLS-1$
                    diagnostics.put("returned", comparison.problems().size()); //$NON-NLS-1$
                    diagnostics.put("truncated", comparison.total() > comparison.problems().size()); //$NON-NLS-1$
                    diagnostics.put("problems", rows); //$NON-NLS-1$
                }
                catch (RuntimeException e)
                {
                    diagnostics.clear();
                    diagnostics.put("complete", false); //$NON-NLS-1$
                    diagnostics.put("reason", PlatformFailures.describe(e)); //$NON-NLS-1$
                }
            }
            var tests = VerificationTestReports.read(paths, before.capturedAt());
            boolean complete = Boolean.TRUE.equals(diagnostics.get("complete")); //$NON-NLS-1$
            String testState = tests.get("state").toString(); //$NON-NLS-1$
            String verdict = added > 0 || "failed".equals(testState) ? "regression" //$NON-NLS-1$ //$NON-NLS-2$
                : !complete || "incomplete".equals(testState) || "skipped".equals(testState) //$NON-NLS-1$ //$NON-NLS-2$
                    ? "incomplete" : "noNewProblems"; //$NON-NLS-1$ //$NON-NLS-2$
            requireOpenProject.accept(before.scope().projectName());
            return ToolResult.success().put("verdict", verdict).put("baselineId", before.id()) //$NON-NLS-1$ //$NON-NLS-2$
                .put("baselineCapturedAt", before.capturedAt().toString()) //$NON-NLS-1$
                .put("capturedAt", capturedAt == null ? null : capturedAt.toString()) //$NON-NLS-1$
                .put("observedAt", Instant.now().toString()).put("scope", before.scope()) //$NON-NLS-1$ //$NON-NLS-2$
                .put("validation", Map.of("state", state.name().toLowerCase(Locale.ROOT), //$NON-NLS-1$ //$NON-NLS-2$
                    "reason", validationReason, "waitSeconds", seconds)) //$NON-NLS-1$ //$NON-NLS-2$
                .put("diagnostics", diagnostics).put("tests", tests) //$NON-NLS-1$ //$NON-NLS-2$
                .put("summary", "Verdict: " + verdict + ". Validation: " + state.name().toLowerCase(Locale.ROOT) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ". Diagnostics: " + (complete ? diagnostics.get("counts") : "incomplete") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ". Selected tests: " + testState + ".") //$NON-NLS-1$ //$NON-NLS-2$
                .put("limitations", "Point-in-time evidence, not an atomic source revision check. Baseline capture " //$NON-NLS-1$ //$NON-NLS-2$
                    + "time is not validation freshness. Identical diagnostics match by signature/count, not identity. " //$NON-NLS-1$
                    + "JUnit paths are caller-selected evidence; timestamps/hashes do not prove project/revision identity. " //$NON-NLS-1$
                    + "noNewProblems permits existing problems; notSelected means tests were not verified.").toJson(); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            return ToolResult.error("Cannot assemble verification_report: " + PlatformFailures.describe(e) //$NON-NLS-1$
                + " Use a retained problem_snapshots baseline and valid report arguments, then retry.").toJson(); //$NON-NLS-1$
        }
    }

    private static int integer(String raw, String key, int fallback, int maximum)
    {
        Integer value = raw == null ? fallback : JsonUtils.parseIntegralInt(raw);
        if (value == null || value < 1 || value > maximum)
            throw new IllegalArgumentException("Invalid " + key + " '" + raw + "'; use an integer from 1 to " + maximum + '.'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return value;
    }
}
