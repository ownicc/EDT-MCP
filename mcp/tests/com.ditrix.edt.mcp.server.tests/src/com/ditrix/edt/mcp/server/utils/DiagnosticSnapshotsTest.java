/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Diagnostic;
import com.ditrix.edt.mcp.server.utils.DiagnosticSnapshots.Scope;

/** Immutable before/after matching, pagination and bounded retention. */
public class DiagnosticSnapshotsTest
{
    private static final String MODULE = "CommonModules/Sales/Module.bsl";
    private static final Scope SCOPE = new Scope("P", null, null, List.of());

    @Test
    public void equalTotalsStillReportFixedAndNewProblems()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        var before = store.capture(SCOPE, List.of(problem("old", 1), problem("shared", 3)));
        var after = store.capture(SCOPE, List.of(problem("new", 1), problem("shared", 3)));
        var result = store.compare(before.id(), after.id(), 100, 0);
        assertEquals(Map.of("new", 1, "fixed", 1, "remaining", 1), result.counts());
        assertEquals(List.of("new", "fixed", "remaining"),
            result.problems().stream().map(change -> change.status()).toList());
        assertEquals("new", result.problems().get(0).after().message());
        assertEquals("old", result.problems().get(1).before().message());
    }

    @Test
    public void lineAndOffsetShiftsPreserveMatchingAndBothLocations()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        var before = store.capture(SCOPE, List.of(problem("same", 3)));
        var after = store.capture(SCOPE, List.of(problem("same", 30)));
        var result = store.compare(before.id(), after.id(), 100, 0);
        assertEquals(Map.of("new", 0, "fixed", 0, "remaining", 1), result.counts());
        var change = result.problems().get(0);
        assertEquals(Integer.valueOf(3), change.before().line());
        assertEquals(Integer.valueOf(30), change.after().line());
        assertEquals(Integer.valueOf(300), change.after().offset());
    }

    @Test
    public void duplicateOccurrencesAreCountedInsteadOfCollapsed()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        var before = store.capture(SCOPE, List.of(problem("same", 20), problem("same", 10)));
        var after = store.capture(SCOPE, List.of(problem("same", 15)));
        assertEquals(Map.of("new", 0, "fixed", 1, "remaining", 1),
            store.compare(before.id(), after.id(), 100, 0).counts());
        assertEquals(Map.of("new", 1, "fixed", 0, "remaining", 1),
            store.compare(after.id(), before.id(), 100, 0).counts());
    }

    @Test
    public void severityContributorCheckAndModuleArePartOfIdentity()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        Diagnostic initial = problem("same", 1);
        var before = store.capture(SCOPE, List.of(initial));
        for (Diagnostic changed : List.of(
            new Diagnostic(MODULE, "ignored", "check", "vendor", "CRITICAL", "same", 1, 10, 1),
            new Diagnostic(MODULE, "ignored", "other", "vendor", "MAJOR", "same", 1, 10, 1),
            new Diagnostic(MODULE, "ignored", "check", "other", "MAJOR", "same", 1, 10, 1),
            new Diagnostic("CommonModules/Other/Module.bsl", "ignored", "check", "vendor", "MAJOR", "same", 1, 10, 1)))
        {
            var after = store.capture(SCOPE, List.of(changed));
            assertEquals(Map.of("new", 1, "fixed", 1, "remaining", 0),
                store.compare(before.id(), after.id(), 100, 0).counts());
        }
    }

    @Test
    public void nonModuleLocationsAndFieldBoundariesCannotCollide()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        var before = store.capture(SCOPE, List.of(
            new Diagnostic(null, "Catalog.A", "a|b", "c", "MAJOR", "same", null, null, null)));
        var after = store.capture(SCOPE, List.of(
            new Diagnostic(null, "Catalog.A", "a", "b|c", "MAJOR", "same", null, null, null)));
        assertEquals(Map.of("new", 1, "fixed", 1, "remaining", 0),
            store.compare(before.id(), after.id(), 100, 0).counts());
        var otherLocation = store.capture(SCOPE, List.of(
            new Diagnostic(null, "Catalog.B", "a|b", "c", "MAJOR", "same", null, null, null)));
        assertEquals(0, store.compare(before.id(), otherLocation.id(), 100, 0).remaining());
    }

    @Test
    public void moduleMatchingIgnoresPresentationAndNormalizesPaths()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        var before = store.capture(SCOPE, List.of(problem("same", 1)));
        var after = store.capture(SCOPE, List.of(new Diagnostic(
            "src\\commonmodules\\sales\\module.bsl", "CommonModule.Sales.Module", "check", "vendor",
            "MAJOR", "same", 8, 80, 1)));
        assertEquals(1, store.compare(before.id(), after.id(), 100, 0).remaining());
    }

    @Test
    public void scopeNormalizationAllowsEquivalentFiltersAndRejectsDifferentScopes()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        var scope = new Scope("P", "major", " CHECK ", List.of("src/" + MODULE, MODULE));
        var same = new Scope("P", "MAJOR", "check", List.of(MODULE.toLowerCase(java.util.Locale.ROOT)));
        var before = store.capture(scope, List.of());
        var after = store.capture(same, List.of());
        assertEquals(0, store.compare(before.id(), after.id(), 100, 0).total());
        for (Scope different : List.of(new Scope("Q", "MAJOR", "check", same.modulePaths()),
            new Scope("P", "MINOR", "check", same.modulePaths()),
            new Scope("P", "MAJOR", "other", same.modulePaths()), SCOPE))
        {
            var other = store.capture(different, List.of());
            assertTrue(assertThrows(IllegalArgumentException.class,
                () -> store.compare(before.id(), other.id(), 100, 0)).getMessage().contains("scope"));
        }
    }

    @Test
    public void snapshotsCopyTheirInputsAndRetainOnlyTenSuccessfulCaptures()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        List<Diagnostic> input = new ArrayList<>(List.of(problem("original", 1)));
        var first = store.capture(SCOPE, input);
        input.clear();
        assertEquals(1, first.problems().size());
        assertThrows(UnsupportedOperationException.class, () -> first.problems().clear());
        for (int i = 1; i < 10; i++)
        {
            store.capture(SCOPE, List.of());
        }
        assertEquals(1, store.compare(first.id(), first.id(), 100, 0).remaining());
        assertThrows(IllegalArgumentException.class,
            () -> store.capture(SCOPE, Collections.nCopies(10001, problem("overflow", 1))));
        assertEquals(1, store.compare(first.id(), first.id(), 100, 0).remaining());
        store.capture(SCOPE, List.of());
        String error = assertThrows(IllegalArgumentException.class,
            () -> store.compare(first.id(), first.id(), 100, 0)).getMessage();
        assertTrue(error, error.contains(first.id()));
        assertTrue(error, error.contains("capture"));
    }

    @Test
    public void exactCaptureLimitIsAcceptedAndPageCountsStayComplete()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        var before = store.capture(SCOPE, List.of());
        var after = store.capture(SCOPE, Collections.nCopies(10000, problem("repeated", 1)));
        var page = store.compare(before.id(), after.id(), 5, 9998);
        assertEquals(10000, page.total());
        assertEquals(Integer.valueOf(10000), page.counts().get("new"));
        assertEquals(2, page.problems().size());
        assertEquals(0, store.compare(before.id(), after.id(), 100, Integer.MAX_VALUE).problems().size());
        assertThrows(IllegalArgumentException.class, () -> store.compare(before.id(), after.id(), 100, -1));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> store.compare("missing-snapshot", after.id(), 100, 0)).getMessage().contains("missing-snapshot"));
    }

    @Test
    public void reversedInputOrderProducesTheSamePages()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        var before = store.capture(SCOPE, List.of());
        var a = store.capture(SCOPE, List.of(problem("z", 30), problem("a", 10)));
        var b = store.capture(SCOPE, List.of(problem("a", 10), problem("z", 30)));
        assertEquals(store.compare(before.id(), a.id(), 1, 1).problems(),
            store.compare(before.id(), b.id(), 1, 1).problems());
    }

    @Test
    public void equalSignaturesWithDifferentPresentationsHaveDeterministicPages()
    {
        DiagnosticSnapshots store = new DiagnosticSnapshots();
        Diagnostic a = new Diagnostic(MODULE, "A", "check", "vendor", "MAJOR", "same", 1, 1, 1);
        Diagnostic b = new Diagnostic(MODULE, "B", "check", "vendor", "MAJOR", "same", 1, 1, 1);
        var before = store.capture(SCOPE, List.of());
        var first = store.capture(SCOPE, List.of(a, b));
        var second = store.capture(SCOPE, List.of(b, a));
        assertEquals(store.compare(before.id(), first.id(), 1, 0).problems(),
            store.compare(before.id(), second.id(), 1, 0).problems());
    }

    private static Diagnostic problem(String message, int line)
    {
        return new Diagnostic(MODULE, "presentation", "check", "vendor", "MAJOR", message, line, line * 10, 1);
    }
}
