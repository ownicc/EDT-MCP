package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Row;

public class BslDiffSearchTest
{
    @Test public void findsLiteralCyrillicOnBothSidesAndSkipsPadding() throws Exception
    {
        var rows = List.of(new Row(0, null), new Row(null, 0), new Row(1, 1));
        var matches = BslDiffSearch.find("КОД\n[Код]", "код\nничего", rows, "код", BslDiffSearch.Scope.BOTH, false);
        assertEquals(3, matches.size());
        assertEquals(new BslDiffSearch.Match(true, 0, 0, 3), matches.get(0));
        assertEquals(new BslDiffSearch.Match(false, 1, 0, 3), matches.get(1));
        assertEquals(new BslDiffSearch.Match(true, 2, 1, 3), matches.get(2));
        assertEquals(1, BslDiffSearch.find("[Код]", "[код]", List.of(new Row(0, 0)), "[Код]", BslDiffSearch.Scope.BOTH, true).size());
    }

    @Test public void scopesAndPhysicalUtf16ColumnsRemainExact() throws Exception
    {
        var rows = List.of(new Row(0, 0), new Row(1, null), new Row(null, 1));
        var matches = BslDiffSearch.find("x\r\n😀слово", "x\nслово", rows, "слово", BslDiffSearch.Scope.OLD, false);
        assertEquals(List.of(new BslDiffSearch.Match(true, 1, 2, 5)), matches);
        assertEquals(List.of(new BslDiffSearch.Match(false, 2, 0, 5)),
            BslDiffSearch.find("x\r\n😀слово", "x\nслово", rows, "слово", BslDiffSearch.Scope.NEW, false));
        assertTrue(BslDiffSearch.find("x", "x", List.of(new Row(0, 0)), "", BslDiffSearch.Scope.BOTH, false).isEmpty());
        assertTrue(BslDiffSearch.find("x", "x", List.of(new Row(0, 0)), "нет", BslDiffSearch.Scope.BOTH, false).isEmpty());
    }

    @Test public void navigationWrapsInEitherDirection()
    {
        assertEquals(0, BslDiffSearch.next(-1, 3, false));
        assertEquals(2, BslDiffSearch.next(-1, 3, true));
        assertEquals(0, BslDiffSearch.next(2, 3, false));
        assertEquals(2, BslDiffSearch.next(0, 3, true));
        assertEquals(-1, BslDiffSearch.next(0, 0, false));
    }
}
