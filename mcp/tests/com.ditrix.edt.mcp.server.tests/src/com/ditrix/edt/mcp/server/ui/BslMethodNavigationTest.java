package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.util.EnumSet;
import java.util.List;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;
import com.ditrix.edt.mcp.server.ui.MethodAwareBslDiff.MethodKind;

public class BslMethodNavigationTest
{
    @Test public void moveHasOneEntryAndCountsOnlyRealEdits() throws Exception
    {
        String a = "#Region A\n" + method("Transfer", "Return 1;") + "#EndRegion\n#Region B\n#EndRegion";
        String b = "#Region A\n#EndRegion\n#Region B\n" + method("Transfer", "Return 2;") + "#EndRegion";
        var comparison = MethodAwareBslDiff.compute(a, b, new NullProgressMonitor());
        assertNull("Parser fallback: " + comparison.fallback(), comparison.fallback());
        var entries = BslMethodNavigation.entries(comparison.diff(), comparison.methods(), comparison.fallback());
        var methods = entries.stream().filter(e -> e.method() != null).toList();
        assertEquals(1, methods.size());
        var entry = methods.get(0);
        assertEquals(MethodKind.MOVED_CHANGED, entry.method().kind());
        assertEquals(1, entry.added());
        assertEquals(1, entry.removed());
        assertEquals(Integer.valueOf(4), comparison.diff().rows().get(entry.row()).right());
        assertTrue(BslMethodNavigation.matches(entry, "TRANSFER", EnumSet.of(MethodKind.CHANGED)));
        assertTrue(BslMethodNavigation.matches(entry, "trans", EnumSet.of(MethodKind.MOVED)));
        assertFalse(BslMethodNavigation.matches(entry, "other", EnumSet.allOf(MethodKind.class)));
        assertFalse(BslMethodNavigation.matches(entry, "", EnumSet.of(MethodKind.ADDED)));
        assertEquals(1, entries.stream().filter(e -> BslMethodNavigation.matches(e, "transfer",
            EnumSet.of(MethodKind.CHANGED, MethodKind.MOVED))).count());
    }

    @Test public void outsideEditsAndOneSidedMethodsHaveValidTargets() throws Exception
    {
        String a = method("Removed", "Return 1;") + "Message(1);";
        String b = method("Added", "Return 2;") + "Message(2);";
        var comparison = MethodAwareBslDiff.compute(a, b, new NullProgressMonitor());
        var entries = BslMethodNavigation.entries(comparison.diff(), comparison.methods(), null);
        assertEquals(3, entries.size());
        for (var entry : entries)
        {
            assertTrue(entry.row() >= 0 && entry.row() < comparison.diff().rows().size());
            assertTrue(entry.changedRows().contains(entry.row()));
            if (entry.method() == null)
            {
                assertEquals(1, entry.added());
                assertEquals(1, entry.removed());
                assertEquals(Integer.valueOf(3), comparison.diff().rows().get(entry.row()).right());
            }
        }
    }

    @Test public void ambiguousNamesExposeOnlyFallback() throws Exception
    {
        String method = method("Duplicate", "Return 1;");
        String a = "#If Client Then\n" + method + "#Else\n" + method + "#EndIf\n";
        var comparison = MethodAwareBslDiff.compute(a, a.replace("Return 1;", "Return 2;"), new NullProgressMonitor());
        var entries = BslMethodNavigation.entries(comparison.diff(), comparison.methods(), comparison.fallback());
        assertEquals(1, entries.size());
        assertNull(entries.get(0).method());
        assertEquals(comparison.fallback(), entries.get(0).fallback());
        assertNotNull(entries.get(0).fallback());
    }

    @Test public void pureMovesAndFormattingDoNotInventChangedLineCounts() throws Exception
    {
        String a = "#Region A\n" + method("Перенос", "Return 1;") + "#EndRegion\n#Region B\n#EndRegion";
        String b = "#Region A\n#EndRegion\n#Region B\n" + method("Перенос", "Return 1;") + "#EndRegion";
        var comparison = MethodAwareBslDiff.compute(a, b, new NullProgressMonitor());
        var entry = BslMethodNavigation.entries(comparison.diff(), comparison.methods(), null).stream().filter(e -> e.method() != null).findFirst().orElseThrow();
        assertEquals(MethodKind.MOVED, entry.method().kind());
        assertEquals(0, entry.added() + entry.removed());
        assertTrue(BslMethodNavigation.matches(entry, "ПЕРЕНОС", EnumSet.of(MethodKind.MOVED)));
        comparison = MethodAwareBslDiff.compute(method("Spacing", "Return 1;"), method("Spacing", "    Return 1;"), new NullProgressMonitor());
        assertNull(comparison.fallback());
        entry = BslMethodNavigation.entries(comparison.diff(), comparison.methods(), null).get(0);
        assertEquals(0, entry.added() + entry.removed());
        assertTrue(entry.row() >= 0);
        assertTrue(BslMethodNavigation.entries(comparison.diff(), List.of(), null).isEmpty());
    }

    private static String method(String name, String body)
    {
        return "Function " + name + "()\n" + body + "\nEndFunction\n";
    }
}
