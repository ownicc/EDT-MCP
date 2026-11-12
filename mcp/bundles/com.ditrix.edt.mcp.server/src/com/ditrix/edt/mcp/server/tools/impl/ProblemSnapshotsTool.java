/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshotCapture;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Change;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Diagnostic;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Scope;
import com.ditrix.edt.mcp.server.utils.MarkerRows;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;

/** Read-only before/after validation reports over bounded immutable snapshots. */
public class ProblemSnapshotsTool implements IMcpTool
{
    public static final String NAME = "problem_snapshots"; //$NON-NLS-1$

    private final DiagnosticSnapshots snapshots;
    private final Function<Scope, List<Diagnostic>> capture;
    private final Consumer<String> requireOpenProject;

    public ProblemSnapshotsTool()
    {
        this(DiagnosticSnapshots.shared());
    }

    ProblemSnapshotsTool(DiagnosticSnapshots snapshots)
    {
        this(snapshots, DiagnosticSnapshotCapture::capture, DiagnosticSnapshotCapture::requireOpenProject);
    }

    ProblemSnapshotsTool(DiagnosticSnapshots snapshots, Function<Scope, List<Diagnostic>> capture,
        Consumer<String> requireOpenProject)
    {
        this.snapshots = snapshots;
        this.capture = capture;
        this.requireOpenProject = requireOpenProject;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Capture stored EDT diagnostics, then compare two snapshots for new, fixed and remaining " //$NON-NLS-1$
            + "signature counts. Does not run validation. Full parameters and examples: call " //$NON-NLS-1$
            + "get_tool_guide('problem_snapshots')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty("action", "capture a complete snapshot or compare two saved snapshots.", true, //$NON-NLS-1$ //$NON-NLS-2$
                "capture", "compare") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "Required for capture; EDT project name from list_projects.") //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty("severity", "Capture only: exact severity filter; omit for all severities.", //$NON-NLS-1$ //$NON-NLS-2$
                "ERRORS", "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL", "NONE") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            .stringProperty("checkId", "Capture only: case-insensitive substring of symbolic check id or short UID.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("modulePaths", "Capture only: exact source-relative BSL module paths; omit for all objects.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("baselineId", "Required for compare: snapshotId captured before editing.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("snapshotId", "Required for compare: snapshotId captured after editing, with the same filters.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Compare only: output page size; default 100, clamped to 1-1000. Counts stay complete.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("offset", "Compare only: nonnegative offset into new, fixed, then remaining rows; default 0.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        // Source/model read-only; repeated captures allocate IDs and can evict older snapshots.
        return new ToolAnnotations(null, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
    }

    @Override
    public String getOutputSchema()
    {
        JsonSchemaBuilder row = JsonSchemaBuilder.object()
            .enumProperty("status", "Signature-count category.", true, "new", "fixed", "remaining") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            .stringProperty("checkId", "Symbolic check id, or the stored short UID when unavailable.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("contributorId", "Check contributor when resolved.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("severity", "Stored EDT marker severity.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Exact stored diagnostic message.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("location", "Observed object presentation.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modulePath", "Source-relative BSL module path when available."); //$NON-NLS-1$ //$NON-NLS-2$
        for (String side : List.of("before", "after")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            row.integerProperty(side + "Line", "Observed 1-based line on this side, when available.") //$NON-NLS-1$ //$NON-NLS-2$
                .integerProperty(side + "Offset", "Stored UTF-16 offset on this side, when available.") //$NON-NLS-1$ //$NON-NLS-2$
                .integerProperty(side + "Length", "Stored UTF-16 length on this side, when available."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty("action", "Completed action.", true, "capture", "compare") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .stringProperty("snapshotId", "Captured snapshot, or the after snapshot of a comparison.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("baselineId", "Before snapshot for compare.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("capturedAt", "Snapshot observation time (ISO-8601), not validation freshness.") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("scope", "Normalized immutable capture filters.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("problemCount", "Complete number of captured matching diagnostics.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("matchingBasis", "signatureCounts: indistinguishable occurrences match by count.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("matchingLimit", "Limits of signature/count matching.") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("counts", "Complete comparison counts, independent of pagination.", //$NON-NLS-1$ //$NON-NLS-2$
                JsonSchemaBuilder.object()
                    .integerProperty("new", "Added occurrences.", true) //$NON-NLS-1$ //$NON-NLS-2$
                    .integerProperty("fixed", "Disappeared occurrences.", true) //$NON-NLS-1$ //$NON-NLS-2$
                    .integerProperty("remaining", "Shared occurrences.", true), false) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("total", "Total comparison rows.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("returned", "Rows on this page.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Effective page size.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("offset", "Requested page offset.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("hasMore", "Whether another comparison page follows.") //$NON-NLS-1$ //$NON-NLS-2$
            .property("problems", "Comparison rows in deterministic category/location order.", //$NON-NLS-1$ //$NON-NLS-2$
                Map.of("type", "array", "items", row.buildMap())) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String action = JsonUtils.extractStringArgument(params, "action"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String severity = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        List<String> modulePaths = JsonUtils.extractArrayArgument(params, "modulePaths"); //$NON-NLS-1$
        String baselineId = JsonUtils.extractStringArgument(params, "baselineId"); //$NON-NLS-1$
        String snapshotId = JsonUtils.extractStringArgument(params, "snapshotId"); //$NON-NLS-1$
        String rawLimit = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        String rawOffset = JsonUtils.extractStringArgument(params, "offset"); //$NON-NLS-1$
        try
        {
            if (!"capture".equals(action) && !"compare".equals(action)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                throw new IllegalArgumentException("action '" + action + "' is invalid; choose capture or compare."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if ("capture".equals(action)) //$NON-NLS-1$
            {
                if (baselineId != null || snapshotId != null || rawLimit != null || rawOffset != null)
                {
                    throw new IllegalArgumentException("baselineId, snapshotId, limit and offset are compare-only. " //$NON-NLS-1$
                        + "Use action='compare', or omit them to capture a complete snapshot."); //$NON-NLS-1$
                }
                if (projectName == null || projectName.isBlank())
                {
                    throw new IllegalArgumentException("projectName is required for capture. Use list_projects to choose a project."); //$NON-NLS-1$
                }
                if (severity != null && !severity.isBlank()
                    && !GetProjectErrorsTool.SEVERITY_VALUES.contains(severity.trim().toUpperCase(Locale.ROOT)))
                {
                    throw new IllegalArgumentException("Invalid severity '" + severity + "'. Choose " //$NON-NLS-1$ //$NON-NLS-2$
                        + String.join(", ", GetProjectErrorsTool.SEVERITY_VALUES) + " or omit severity."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                String unusable = MarkerRows.unusableModulePathsError(modulePaths);
                if (unusable != null)
                {
                    throw new IllegalArgumentException(unusable);
                }
                Scope scope = new Scope(projectName.trim(), severity, checkId, modulePaths);
                var captured = snapshots.capture(scope, capture.apply(scope));
                return ToolResult.success().put("action", action).put("snapshotId", captured.id()) //$NON-NLS-1$ //$NON-NLS-2$
                    .put("scope", scope).put("capturedAt", captured.capturedAt().toString()) //$NON-NLS-1$ //$NON-NLS-2$
                    .put("problemCount", captured.problems().size()).toJson(); //$NON-NLS-1$
            }
            if (projectName != null || severity != null || checkId != null || modulePaths != null)
            {
                throw new IllegalArgumentException("projectName, severity, checkId and modulePaths are capture-only. " //$NON-NLS-1$
                    + "Compare uses the filters saved in each snapshot; omit these arguments."); //$NON-NLS-1$
            }
            if (baselineId == null || baselineId.isBlank())
            {
                throw new IllegalArgumentException("baselineId is required for compare. Pass the snapshotId from the before capture."); //$NON-NLS-1$
            }
            if (snapshotId == null || snapshotId.isBlank())
            {
                throw new IllegalArgumentException("snapshotId is required for compare. Pass the snapshotId from the after capture."); //$NON-NLS-1$
            }
            int limit = integer(rawLimit, "limit", Pagination.DEFAULT_LIMIT); //$NON-NLS-1$
            int offset = integer(rawOffset, "offset", 0); //$NON-NLS-1$
            if (offset < 0)
            {
                throw new IllegalArgumentException("Invalid offset '" + rawOffset + "'; use 0 or a positive integer."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            var before = snapshots.require(baselineId);
            snapshots.require(snapshotId);
            requireOpenProject.accept(before.scope().projectName());
            var comparison = snapshots.compare(baselineId, snapshotId, limit, offset);
            return ToolResult.success().put("action", action).put("baselineId", baselineId) //$NON-NLS-1$ //$NON-NLS-2$
                .put("snapshotId", snapshotId).put("scope", before.scope()) //$NON-NLS-1$ //$NON-NLS-2$
                .put("matchingBasis", "signatureCounts") //$NON-NLS-1$ //$NON-NLS-2$
                .put("matchingLimit", "Identical occurrences match by count, not individual identity. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Replacing one occurrence with an indistinguishable one can appear remaining.") //$NON-NLS-1$
                .put("counts", comparison.counts()).put("total", comparison.total()) //$NON-NLS-1$ //$NON-NLS-2$
                .put("returned", comparison.problems().size()).put("limit", comparison.limit()) //$NON-NLS-1$ //$NON-NLS-2$
                .put("offset", offset).put("hasMore", (long)offset + comparison.problems().size() < comparison.total()) //$NON-NLS-1$ //$NON-NLS-2$
                .put("problems", comparison.problems().stream().map(ProblemSnapshotsTool::row).toList()).toJson(); //$NON-NLS-1$
        }
        catch (IllegalArgumentException | IllegalStateException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        catch (RuntimeException e)
        {
            return ToolResult.error("Could not " + action + " diagnostic snapshots for project '" + projectName //$NON-NLS-1$ //$NON-NLS-2$
                + "': " + PlatformFailures.describe(e) //$NON-NLS-1$
                + ". Complete revalidate_objects, wait for the project to be ready, then retry problem_snapshots.").toJson(); //$NON-NLS-1$
        }
    }

    private static int integer(String raw, String name, int fallback)
    {
        if (raw == null)
        {
            return fallback;
        }
        Integer parsed = JsonUtils.parseIntegralInt(raw);
        if (parsed == null)
        {
            throw new IllegalArgumentException("Invalid " + name + " '" + raw + "'; pass an integer value."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return parsed;
    }

    private static Map<String, Object> row(Change change)
    {
        Diagnostic diagnostic = change.after() != null ? change.after() : change.before();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", change.status()); //$NON-NLS-1$
        row.put("checkId", diagnostic.checkId()); //$NON-NLS-1$
        row.put("contributorId", diagnostic.contributorId()); //$NON-NLS-1$
        row.put("severity", diagnostic.severity()); //$NON-NLS-1$
        row.put("message", diagnostic.message()); //$NON-NLS-1$
        row.put("location", diagnostic.location()); //$NON-NLS-1$
        row.put("modulePath", diagnostic.modulePath()); //$NON-NLS-1$
        coordinates(row, "before", change.before()); //$NON-NLS-1$
        coordinates(row, "after", change.after()); //$NON-NLS-1$
        return row;
    }

    private static void coordinates(Map<String, Object> row, String prefix, Diagnostic diagnostic)
    {
        if (diagnostic != null)
        {
            row.put(prefix + "Line", diagnostic.line()); //$NON-NLS-1$
            row.put(prefix + "Offset", diagnostic.offset()); //$NON-NLS-1$
            row.put(prefix + "Length", diagnostic.length()); //$NON-NLS-1$
        }
    }
}
