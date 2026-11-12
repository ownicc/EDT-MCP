/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Scope;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/** Complete collection and refusals instead of silently dropping unreadable diagnostics. */
public class DiagnosticSnapshotCaptureTest
{
    @Test
    public void collectorCopiesRowsAndAppliesExistingFilters()
    {
        IProject project = project("P");
        Marker wanted = marker(project, "CommonModules/Продажи/Module.bsl", "Ошибка", MarkerSeverity.MAJOR);
        Marker excluded = marker(project, "CommonModules/Other/Module.bsl", "other", MarkerSeverity.MINOR);
        ICheckRepository repository = mock(ICheckRepository.class);
        CheckUid uid = mock(CheckUid.class);
        when(uid.getCheckId()).thenReturn("symbolic-check");
        when(uid.getContributorId()).thenReturn("vendor");
        when(repository.getUidForShortUid("SU1", project)).thenReturn(uid);
        var rows = DiagnosticSnapshotCapture.collect(manager(wanted, excluded), project, repository,
            new Scope("P", "MAJOR", "symbolic", List.of("src/CommonModules/Продажи/Module.bsl")));
        assertEquals(1, rows.size());
        assertEquals("symbolic-check", rows.get(0).checkId());
        assertEquals("vendor", rows.get(0).contributorId());
        assertEquals("Ошибка", rows.get(0).message());
        assertEquals("MAJOR", rows.get(0).severity());
        assertEquals(Integer.valueOf(3), rows.get(0).line());
        when(wanted.getMessage()).thenReturn("changed");
        assertEquals("Ошибка", rows.get(0).message());
        verify(excluded, never()).getObjectPresentation();
    }

    @Test
    public void unreadablePresentationAndCheckResolutionRefuseTheCapture()
    {
        IProject project = project("P");
        Marker marker = marker(project, null, "message", MarkerSeverity.MAJOR);
        when(marker.getObjectPresentation()).thenThrow(new IllegalStateException("unreadable model"));
        Scope scope = new Scope("P", null, null, List.of());
        assertTrue(assertThrows(IllegalStateException.class,
            () -> DiagnosticSnapshotCapture.collect(manager(marker), project, mock(ICheckRepository.class), scope))
            .getMessage().contains("incomplete"));
        doReturn("Catalog.X").when(marker).getObjectPresentation();
        ICheckRepository broken = mock(ICheckRepository.class);
        when(broken.getUidForShortUid("SU1", project)).thenThrow(new IllegalStateException("repository unavailable"));
        assertThrows(IllegalStateException.class,
            () -> DiagnosticSnapshotCapture.collect(manager(marker), project, broken, scope));
    }

    @Test
    public void blankNonModuleLocationAndMissingSeverityAreNotCleanSnapshots()
    {
        IProject project = project("P");
        Marker marker = marker(project, null, "message", MarkerSeverity.MAJOR);
        when(marker.getObjectPresentation()).thenReturn("");
        Scope scope = new Scope("P", null, null, List.of());
        assertThrows(IllegalStateException.class,
            () -> DiagnosticSnapshotCapture.collect(manager(marker), project, mock(ICheckRepository.class), scope));
        when(marker.getObjectPresentation()).thenReturn("Catalog.X");
        when(marker.getSeverity()).thenReturn(null);
        assertThrows(IllegalStateException.class,
            () -> DiagnosticSnapshotCapture.collect(manager(marker), project, mock(ICheckRepository.class), scope));
    }

    @Test
    public void capIsAppliedToMatchingRowsAndOtherProjectsAreExcluded()
    {
        IProject project = project("P");
        Marker wanted = marker(project, null, "message", MarkerSeverity.MAJOR);
        Marker foreign = marker(project("Q"), null, "other", MarkerSeverity.MAJOR);
        Scope scope = new Scope("P", null, null, List.of());
        assertEquals(1, DiagnosticSnapshotCapture.collect(manager(foreign, wanted), project,
            mock(ICheckRepository.class), scope).size());
        IMarkerManager overflowing = mock(IMarkerManager.class);
        when(overflowing.markers()).thenAnswer(inv -> Stream.generate(() -> wanted).limit(10001));
        String error = assertThrows(IllegalArgumentException.class,
            () -> DiagnosticSnapshotCapture.collect(overflowing, project, mock(ICheckRepository.class), scope))
            .getMessage();
        assertTrue(error, error.contains("10000"));
        assertTrue(error, error.contains("Narrow"));
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static IMarkerManager manager(Marker... markers)
    {
        IMarkerManager manager = mock(IMarkerManager.class);
        when(manager.markers()).thenAnswer(inv -> Stream.of(markers));
        return manager;
    }

    private static Marker marker(IProject project, String module, String message, MarkerSeverity severity)
    {
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(project);
        when(marker.getSeverity()).thenReturn(severity);
        when(marker.getCheckId()).thenReturn("SU1");
        when(marker.getMessage()).thenReturn(message);
        when(marker.getObjectPresentation()).thenReturn("CommonModule.Продажи.Module");
        ExtraInfo info = new ExtraInfo();
        if (module != null)
        {
            info.put("uriToProblem", "platform:/resource/P/src/" + module + "#/0");
            info.put("line", "3");
        }
        when(marker.getExtraInfo()).thenReturn(info);
        return marker;
    }

    private static final class ExtraInfo extends HashMap<String, String> implements IExtraInfoMap
    {
        private static final long serialVersionUID = 1L;
    }
}
