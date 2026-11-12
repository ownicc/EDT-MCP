/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.*;

import org.junit.Test;

public class ComparisonTextDiffTest
{
    private static ComparisonTextDiff.Source text(String value)
    {
        return ComparisonTextDiff.Source.available("CommonModule.Test.Module", value);
    }

    private static String diff(String left, String right, int context, int limit)
    {
        return ComparisonTextDiff.render(text(left), text(right), "Main", "Other", context, limit);
    }

    @Test
    public void showsActualEditsWithOriginalLineNumbers()
    {
        String result = diff("Procedure Test()\n    Value = 1;\nEndProcedure\n",
            "Procedure Test()\n    Value = 2;\nEndProcedure\n", 1, 100);
        assertTrue(result, result.contains("@@ -1,3 +1,3 @@"));
        assertTrue(result, result.contains("-    Value = 1;\n+    Value = 2;"));
        assertTrue(result, result.contains("--- Main\n+++ Other"));
        assertTrue(result, result.contains("whole owning module"));
    }

    @Test
    public void distinguishesAbsentAndExistingEmptyModules()
    {
        String result = ComparisonTextDiff.render(ComparisonTextDiff.Source.absent(""), text(""),
            "Ancestor", "Main", 3, 100);
        assertTrue(result, result.contains("Ancestor: absent"));
        assertTrue(result, result.contains("Main: present"));
        assertTrue(result, result.contains("equal"));
        assertFalse(result, result.contains("No differences"));
    }

    @Test
    public void unreadableTextCannotBecomeDeletionOrEquality()
    {
        String result = ComparisonTextDiff.render(text("Return 1;\n"),
            ComparisonTextDiff.Source.unavailable("Test.Module", "Could not read module text"),
            "Main", "Other", 3, 100);
        assertTrue(result, result.contains("Could not read module text"));
        assertFalse(result, result.contains("texts are equal"));
        assertFalse(result, result.contains("-Return"));
    }

    @Test
    public void insertionAndDeletionOfModuleHaveZeroLengthSide()
    {
        String added = ComparisonTextDiff.render(ComparisonTextDiff.Source.absent(""), text("A\nB\n"),
            "Main", "Other", 0, 100);
        assertTrue(added, added.contains("@@ -0,0 +1,2 @@\n+A\n+B\n"));
        String removed = ComparisonTextDiff.render(text("A\nB\n"), ComparisonTextDiff.Source.absent(""),
            "Main", "Other", 0, 100);
        assertTrue(removed, removed.contains("@@ -1,2 +0,0 @@\n-A\n-B\n"));
    }

    @Test
    public void preservesUnicodeBomCrLfAndMissingFinalNewline()
    {
        String result = diff("\ufeffПроцедура Тест()\r\n    А = 1;\r\nКонецПроцедуры",
            "\ufeffПроцедура Тест()\r\n    А = 2;\r\nКонецПроцедуры", 1, 100);
        assertTrue(result, result.contains("\ufeffПроцедура Тест()\r\n"));
        assertTrue(result, result.contains("-    А = 1;\r\n+    А = 2;\r\n"));
        assertTrue(result, result.contains("\\ No newline at end of file"));
    }

    @Test
    public void terminalNewlineOnlyChangeStillHasAHunk()
    {
        String result = diff("Return 1;", "Return 1;\n", 0, 100);
        assertTrue(result, result.contains("-Return 1;\n\\ No newline at end of file\n+Return 1;\n"));
    }

    @Test
    public void outputLimitIsExplicitAndKeepsOnlyCompleteLines()
    {
        String result = diff("А\nБ\nВ\n", "Г\nД\nЕ\n", 0, 2);
        assertTrue(result, result.contains("truncated"));
        assertTrue(result, result.contains("@@ -1,3 +1,3 @@\n-А\n"));
        assertFalse(result, result.contains("\ufffd"));
        assertFalse(result, result.contains("equal"));
    }

    @Test
    public void longLineCannotExceedOutputByteBound()
    {
        String result = diff("short\n", "Ж".repeat(100_000) + "\n", 0, 2000);
        assertTrue(result, result.contains("truncated"));
        assertTrue(result, result.length() < 66_000);
        assertFalse(result, result.contains("\ufffd"));
    }

    @Test
    public void oversizedInputIsRefusedWithoutClaimingEquality()
    {
        String result = diff("x".repeat(500_001), "x", 0, 100);
        assertTrue(result, result.contains("input limit"));
        assertFalse(result, result.contains("equal"));
        result = diff("x\n".repeat(20_001), "x", 0, 100);
        assertTrue(result, result.contains("input limit"));
    }

    @Test
    public void separatePartialLinesShareTheSectionInputLineBudget()
    {
        for (int firstLines : new int[] {19_999, 20_000})
        {
            String first = "x\n".repeat(firstLines - 1) + "x";
            var source = new ComparisonTextDiff.Source("Test.Module", first + "y", false, null,
                java.util.List.of(new ComparisonTextDiff.Fragment(first, 0, 1, 1),
                    new ComparisonTextDiff.Fragment("y", first.length() + 100, 20_010, 1)));
            String report = ComparisonTextDiff.render(source, source, "Main", "Other", 0, 20);
            assertEquals(report, firstLines == 20_000, report.contains("input limit"));
            assertEquals(report, firstLines == 19_999, report.contains("selected section texts are equal"));
        }
    }

    @Test
    public void literalMarkdownFenceCannotEndTheDiffBlock()
    {
        String result = diff("// before\n", "```\n// after\n", 0, 100);
        assertTrue(result, result.contains("````diff\n"));
        assertTrue(result, result.contains("+```\n"));
        assertTrue(result, result.endsWith("````\n\n"));
    }
}
