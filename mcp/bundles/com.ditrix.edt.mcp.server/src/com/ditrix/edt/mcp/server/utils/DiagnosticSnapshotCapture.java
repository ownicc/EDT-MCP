/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Diagnostic;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Scope;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/** Copies a complete filtered marker set under the project's BM read boundary. */
public final class DiagnosticSnapshotCapture
{
    private DiagnosticSnapshotCapture()
    {
    }

    /** Historical comparisons still require an existing, open workspace project. */
    public static IProject requireOpenProject(String projectName)
    {
        ProjectContext context = ProjectContext.of(projectName);
        if (!context.exists())
        {
            throw new IllegalArgumentException(ProjectContext.notFoundMessage(projectName));
        }
        if (!context.isOpen())
        {
            throw new IllegalArgumentException("Project '" + projectName //$NON-NLS-1$
                + "' is closed. Open it in EDT, then retry problem_snapshots."); //$NON-NLS-1$
        }
        return context.project();
    }

    public static List<Diagnostic> capture(Scope scope)
    {
        IProject project = requireOpenProject(scope.projectName());
        requireReady(project);
        for (String path : scope.modulePaths())
        {
            var file = BslModuleUtils.resolveModuleFile(project, path);
            if (file == null || !file.exists())
            {
                throw new IllegalArgumentException("modulePaths contains missing module '" + path //$NON-NLS-1$
                    + "'. Use list_modules to copy an existing module path, or omit modulePaths."); //$NON-NLS-1$
            }
        }
        Activator activator = Activator.getDefault();
        if (activator == null || activator.getMarkerManager() == null
            || activator.getBmModelManager() == null || activator.getCheckRepository() == null)
        {
            throw incomplete("EDT marker, model or check services are unavailable"); //$NON-NLS-1$
        }
        IBmModel model = activator.getBmModelManager().getModel(project);
        if (model == null)
        {
            throw incomplete("project '" + scope.projectName() + "' has no readable BM model"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<Diagnostic> problems = BmTransactions.read(model, "CaptureDiagnosticSnapshot", (tx, pm) -> //$NON-NLS-1$
            collect(activator.getMarkerManager(), project, activator.getCheckRepository(), scope));
        requireReady(project);
        return problems;
    }

    private static void requireReady(IProject project)
    {
        String error = ProjectStateChecker.checkReadyOrError(project);
        if (error != null)
        {
            throw incomplete(error);
        }
    }

    /** Caller owns the BM read boundary. No result cap is applied before filtering. */
    static List<Diagnostic> collect(IMarkerManager manager, IProject project,
        ICheckRepository repository, Scope scope)
    {
        List<Diagnostic> result = new ArrayList<>();
        Set<String> moduleKeys = Set.copyOf(scope.modulePaths());
        MarkerSeverity severity = scope.severity().isEmpty() ? null : MarkerSeverity.valueOf(scope.severity());
        try (Stream<Marker> markers = manager.markers())
        {
            var iterator = markers.iterator();
            while (iterator.hasNext())
            {
                Marker marker = iterator.next();
                if (!project.equals(marker.getProject()))
                {
                    continue;
                }
                MarkerSeverity observedSeverity = marker.getSeverity();
                if (observedSeverity == null)
                {
                    throw incomplete("a marker has no readable severity"); //$NON-NLS-1$
                }
                int[] unresolved = {0};
                // Resolve the UID strictly below: the listing helper intentionally suppresses
                // repository failures, but suppressing one here could report a false fix.
                MarkerRows.Row row = MarkerRows.buildIfMatches(marker, severity, null,
                    Collections.emptySet(), moduleKeys, null, null, unresolved, new int[1], false,
                    MarkerRows.Row::new);
                if (row == null)
                {
                    continue;
                }
                CheckUid uid;
                try
                {
                    uid = repository.getUidForShortUid(row.checkCode, project);
                }
                catch (RuntimeException e)
                {
                    throw incomplete("could not resolve check '" + row.checkCode + "': " //$NON-NLS-1$ //$NON-NLS-2$
                        + PlatformFailures.describe(e));
                }
                String checkId = uid == null ? null : uid.getCheckId();
                if (!scope.checkId().isEmpty()
                    && !MarkerRows.checkIdMatches(row.checkCode, checkId, scope.checkId()))
                {
                    continue;
                }
                if (unresolved[0] != 0 || row.objectPresentation == null || row.objectPresentation.isBlank()
                    || row.checkCode == null || row.checkCode.isBlank())
                {
                    throw incomplete("a marker has an unreadable location or check identity"); //$NON-NLS-1$
                }
                if (result.size() == DiagnosticSnapshots.MAX_PROBLEMS)
                {
                    throw new IllegalArgumentException("Snapshot exceeds " + DiagnosticSnapshots.MAX_PROBLEMS //$NON-NLS-1$
                        + " matching problems. Narrow severity, checkId or modulePaths and capture again."); //$NON-NLS-1$
                }
                result.add(new Diagnostic(row.modulePath, row.objectPresentation,
                    checkId == null || checkId.isBlank() ? row.checkCode : checkId,
                    uid == null ? null : uid.getContributorId(), observedSeverity.name(), row.message,
                    row.line, row.offset, row.length));
            }
        }
        return result;
    }

    private static IllegalStateException incomplete(String detail)
    {
        return new IllegalStateException("Diagnostic snapshot capture is incomplete: " + detail //$NON-NLS-1$
            + ". Complete revalidate_objects, wait for list_projects to report ready, then capture again."); //$NON-NLS-1$
    }
}
