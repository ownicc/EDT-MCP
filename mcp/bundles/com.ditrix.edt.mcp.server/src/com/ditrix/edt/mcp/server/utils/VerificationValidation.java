/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;

/** Observes existing validation for every metadata top object without scheduling new work. */
public final class VerificationValidation
{
    private VerificationValidation() { }

    public static ValidationState await(String projectName, long timeoutMs)
    {
        if (WrittenObjectMarkers.PLATFORM.checksDisabled()) return ValidationState.UNOBSERVABLE;
        var project = DiagnosticSnapshotCapture.requireOpenProject(projectName);
        long started = System.nanoTime();
        var returned = BuildUtils.beginMarkerRead(projectName, timeoutMs);
        if (returned == null) return ValidationState.PENDING;
        AtomicReference<List<Long>> ids = new AtomicReference<>();
        var result = BoundedJob.run("Resolving verification scope for " + projectName, timeoutMs, monitor -> {
            var activator = Activator.getDefault();
            var manager = activator == null ? null : activator.getBmModelManager();
            var model = manager == null ? null : manager.getModel(project);
            if (model == null) return;
            ids.set(BmTransactions.read(model, "VerificationScope", (tx, pm) -> {
                List<Long> values = new ArrayList<>();
                var objects = tx.getTopObjectIterator();
                while (objects.hasNext())
                {
                    var object = objects.next();
                    if (object instanceof MdObject && object.bmGetId() > 0) values.add(object.bmGetId());
                }
                return List.copyOf(values);
            }));
        }, () -> returned.set(true));
        if (result.getFailure() != null || ids.get() == null)
            return result.getOutcome() == BoundedJob.Outcome.TIMED_OUT
                ? ValidationState.PENDING : ValidationState.UNOBSERVABLE;
        if (result.getOutcome() != BoundedJob.Outcome.COMPLETED) return ValidationState.PENDING;
        long remaining = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (remaining <= 0) return ValidationState.PENDING;
        return BuildUtils.waitForObjectValidation(project, ids.get(), remaining);
    }
}
