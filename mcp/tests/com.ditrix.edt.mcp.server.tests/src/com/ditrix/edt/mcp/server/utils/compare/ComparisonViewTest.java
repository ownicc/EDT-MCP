/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.bsl.compare.BslModuleComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionType;
import com._1c.g5.v8.dt.bsl.compare.BslCompareFactory;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Unit tests for {@link ComparisonView}, and deliberately for ONE of its accessors.
 * <p>
 * The class is a wall rather than a computation: twenty one-line delegations whose whole job is to
 * expose the reading half of {@code IComparisonSession} and nothing else. Pinning each of them
 * would pin the wall to itself. {@link ComparisonView#isGlobalScope()} is different, because
 * something downstream is DECIDED by it: {@code get_comparison_node} turns its answer into
 * {@link ComparisonNodeRenderer.ContentCoverage}, which governs whether the document tells the
 * caller that content was excluded somewhere. The renderer's own tests drive the classification
 * from a boolean; this one pins where that boolean comes from, so the two halves together cover
 * the claim end to end.
 * <p>
 * It matters WHICH answer it is. The session computes {@code isGlobalScope} once, in its
 * constructor, and keeps it - the same value that decided {@code mergeObjectsContent} at launch -
 * while the scope OBJECT grows during the run as the engine pulls dependencies in. Reading the
 * saved answer is therefore the difference between describing the run the caller started and
 * describing the scope it ended up with.
 */
public class ComparisonViewTest
{
    @Test
    public void publicReaderResolvesThePlatformConverter() throws Exception
    {
        IComparisonSession session = mock(IComparisonSession.class);
        BslModuleComparisonNode module = mock(BslModuleComparisonNode.class);
        when(module.getSymlink(ComparisonSide.MAIN)).thenReturn("CommonModule.Test.Module");
        IComparisonDataSource data = mock(IComparisonDataSource.class);
        when(session.getDataSource(ComparisonSide.MAIN)).thenReturn(data);
        when(data.fileExists(any(Path.class))).thenReturn(true);
        when(data.getFileStream(any(Path.class))).thenReturn(new ByteArrayInputStream(
            "// public reader\n".getBytes(StandardCharsets.UTF_8)));
        ComparisonTextDiff.Source source = new ComparisonView(null, session).moduleText(module, ComparisonSide.MAIN);
        assertEquals(source.problem(), "// public reader\n", source.text());
    }

    @Test
    public void streamReadFailureDoesNotBecomeAnEmptyExistingModule() throws Exception
    {
        IComparisonSession session = mock(IComparisonSession.class);
        BslModuleComparisonNode module = mock(BslModuleComparisonNode.class);
        when(module.getSymlink(ComparisonSide.MAIN)).thenReturn("Test.Module");
        IQualifiedNameFilePathConverter converter = mock(IQualifiedNameFilePathConverter.class);
        when(converter.getFilePath("Test.Module")).thenReturn("test.bsl");
        IComparisonDataSource data = mock(IComparisonDataSource.class);
        when(session.getDataSource(ComparisonSide.MAIN)).thenReturn(data);
        when(data.fileExists(Path.of("test.bsl"))).thenReturn(true);
        when(data.getFileStream(Path.of("test.bsl"))).thenReturn(new java.io.InputStream()
        {
            @Override
            public int read() throws java.io.IOException
            {
                throw new java.io.IOException("fixture read failure");
            }
        });
        ComparisonTextDiff.Source source = new ComparisonView(null, session)
            .moduleText(module, ComparisonSide.MAIN, () -> converter);
        assertFalse(source.absent());
        assertTrue(source.problem(), source.problem().contains("Could not read"));
        org.junit.Assert.assertNull(source.text());
    }

    @Test
    public void readsActualComparisonSideStreamsIncludingAncestor() throws Exception
    {
        IComparisonSession session = mock(IComparisonSession.class);
        when(session.isThreeWay()).thenReturn(true);
        BslModuleComparisonNode module = mock(BslModuleComparisonNode.class);
        IQualifiedNameFilePathConverter converter = mock(IQualifiedNameFilePathConverter.class);
        ComparisonView view = new ComparisonView(null, session);
        for (ComparisonSide side : new ComparisonSide[] {ComparisonSide.MAIN,
            ComparisonSide.OTHER, ComparisonSide.COMMON_ANCESTOR})
        {
            String name = "CommonModule." + side.name() + ".Module";
            String content = "// " + side.name() + "\n";
            Path path = Path.of(side.name() + ".bsl");
            when(module.getSymlink(side)).thenReturn(name);
            when(converter.getFilePath(name)).thenReturn(path.toString());
            IComparisonDataSource data = mock(IComparisonDataSource.class);
            when(session.getDataSource(side)).thenReturn(data);
            when(data.fileExists(path)).thenReturn(true);
            when(data.getFileStream(path)).thenReturn(new ByteArrayInputStream(
                content.getBytes(StandardCharsets.UTF_8)));
            ComparisonTextDiff.Source source = view.moduleText(module, side, () -> converter);
            assertEquals(name, source.module());
            assertEquals(content, source.text());
        }
    }

    @Test
    public void sectionPreservesModuleReadFailuresAndSideAbsence()
    {
        ComparisonView view = new ComparisonView(null, mock(IComparisonSession.class));
        BslModuleComparisonNode module = mock(BslModuleComparisonNode.class);
        BslModuleSectionComparisonNode section = mock(BslModuleSectionComparisonNode.class);
        when(section.getParent()).thenReturn(module);
        assertTrue(view.moduleText(section, ComparisonSide.MAIN, () -> null).absent());
        when(module.getSymlink(ComparisonSide.MAIN)).thenReturn("Test.Module");
        ComparisonTextDiff.Source unavailable = view.moduleText(section, ComparisonSide.MAIN, () -> null);
        assertFalse(unavailable.absent());
        assertTrue(unavailable.problem().contains("converter"));
        ComparisonTextDiff.Source noAncestor = view.moduleText(module, ComparisonSide.COMMON_ANCESTOR, () -> null);
        assertFalse(noAncestor.absent());
        assertTrue(noAncestor.problem().contains("two-way"));
    }

    @Test
    public void binaryModuleIsUnavailableRatherThanDecodedAsText() throws Exception
    {
        IComparisonSession session = mock(IComparisonSession.class);
        BslModuleComparisonNode module = mock(BslModuleComparisonNode.class);
        when(module.getSymlink(ComparisonSide.MAIN)).thenReturn("Test.Module");
        IQualifiedNameFilePathConverter converter = mock(IQualifiedNameFilePathConverter.class);
        when(converter.getFilePath("Test.Module")).thenReturn("test.bsl");
        IComparisonDataSource data = mock(IComparisonDataSource.class);
        when(session.getDataSource(ComparisonSide.MAIN)).thenReturn(data);
        when(data.fileExists(Path.of("test.bsl"))).thenReturn(true);
        // EDT's binary BSL header: FF FF FF 7F 00 02.
        when(data.getFileStream(Path.of("test.bsl"))).thenReturn(new ByteArrayInputStream(
            new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f, 0, 2}));
        ComparisonTextDiff.Source source = new ComparisonView(null, session)
            .moduleText(module, ComparisonSide.MAIN, () -> converter);
        assertFalse(source.absent());
        assertTrue(source.problem(), source.problem().contains("Binary module"));
        org.junit.Assert.assertNull(source.text());
    }

    @Test
    public void regionOwnTextExcludesChangedChildMethods() throws Exception
    {
        String before = "#Область Расчеты\nПроцедура Тест()\n    Сумма = 100;\nКонецПроцедуры\n#КонецОбласти\n";
        String after = before.replace("100", "200");
        var region = section(BslModuleSectionType.REGION_PREPROCESSOR);
        pairedRange(region, 0, "#Область Расчеты\n".length(), 0, "#Область Расчеты\n".length());
        pairedRange(region, before.indexOf("#КонецОбласти"), "#КонецОбласти\n".length(),
            after.indexOf("#КонецОбласти"), "#КонецОбласти\n".length());
        var child = section(BslModuleSectionType.PROCEDURE);
        region.getChildren().add(child);
        String report = sectionDiff(region, before, after);
        assertTrue(report, report.contains("selected section texts are equal"));
        assertFalse(report, report.contains("Сумма"));
        assertFalse(report, report.contains("whole owning module"));
    }

    @Test
    public void changedRegionHeaderUsesItsOwnOriginalLines() throws Exception
    {
        String before = "// prefix\n#Область До\nПроцедура Тест()\nКонецПроцедуры\n#КонецОбласти\n";
        String after = before.replace("Область До", "Область После").replace("Тест()", "Другой()");
        var region = section(BslModuleSectionType.REGION_PREPROCESSOR);
        pairedRange(region, before.indexOf('#'), "#Область До\n".length(),
            after.indexOf('#'), "#Область После\n".length());
        pairedRange(region, before.indexOf("#Конец"), "#КонецОбласти\n".length(), after.indexOf("#Конец"), "#КонецОбласти\n".length());
        String report = sectionDiff(region, before, after);
        assertTrue(report, report.contains("@@ -2 +2 @@"));
        assertTrue(report, report.contains("-#Область До\n+#Область После"));
        assertFalse(report, report.contains("Другой"));
    }

    @Test
    public void splitMethodIncludesCommentsSignatureAndBodyAtOriginalCoordinates() throws Exception
    {
        String before = "// prefix\n// Описание\nПроцедура Тест()\n    А = 1;\nКонецПроцедуры\n";
        String after = "// another prefix\n" + before.replace("Тест()", "Тест(Параметр)").replace("А = 1", "А = 2");
        var method = section(BslModuleSectionType.PROCEDURE);
        var signature = section(BslModuleSectionType.METHOD_SIGNATURE);
        var body = section(BslModuleSectionType.METHOD_BODY);
        method.getChildren().add(signature);
        method.getChildren().add(body);
        pairedRange(signature, before.indexOf("// Описание"), before.indexOf("    А") - before.indexOf("// Описание"),
            after.indexOf("// Описание"), after.indexOf("    А") - after.indexOf("// Описание"));
        pairedRange(signature, before.indexOf("КонецПроцедуры"), "КонецПроцедуры\n".length(), after.indexOf("КонецПроцедуры"), "КонецПроцедуры\n".length());
        pairedRange(body, before.indexOf("    А"), "    А = 1;\n".length(), after.indexOf("    А"), "    А = 2;\n".length());
        String report = sectionDiff(method, before, after);
        assertTrue(report, report.contains("// Описание"));
        assertTrue(report, report.contains("-Процедура Тест()\n+Процедура Тест(Параметр)"));
        assertTrue(report, report.contains("@@ -4 +5 @@"));
        assertTrue(report, report.contains("-    А = 1;\n+    А = 2;"));
        assertFalse(report, report.contains("another prefix"));
    }

    @Test
    public void invalidOrMissingSectionRangesNeverClaimEquality() throws Exception
    {
        for (int[] range : new int[][] {{-1, 2}, {0, -1}, {1, Integer.MAX_VALUE}, {9, 2}})
        {
            var region = section(BslModuleSectionType.MAIN);
            pairedRange(region, range[0], range[1], range[0], range[1]);
            String report = sectionDiff(region, "text\n", "text\n");
            assertTrue(report, report.contains("unavailable"));
            assertFalse(report, report.contains("texts are equal"));
        }
        var missing = section(BslModuleSectionType.REGION_PREPROCESSOR);
        String report = sectionDiff(missing, "text\n", "text\n");
        assertTrue(report, report.contains("unavailable"));
        assertFalse(report, report.contains("texts are equal"));
    }

    @Test
    public void disjointFragmentsNeverUseJoinedLineNumbersOrInterveningText() throws Exception
    {
        String before = "А = 1;\nПроцедура Скрытая()\nКонецПроцедуры\nБ = 1;\n";
        String after = before.replace("Б = 1", "Б = 2");
        var main = section(BslModuleSectionType.MAIN);
        pairedRange(main, 0, 7, 0, 7);
        pairedRange(main, before.indexOf("Б = 1"), 7, after.indexOf("Б = 2"), 7);
        String report = sectionDiff(main, before, after);
        assertTrue(report, report.contains("@@ -4 +4 @@"));
        assertFalse(report, report.contains("Скрытая"));
        assertFalse(report, report.contains("@@ -2 +2 @@"));
    }

    @Test
    public void absentSectionDoesNotBecomeWholeModuleDeletion() throws Exception
    {
        var section = section(BslModuleSectionType.MAIN);
        when(section.getSymlink(ComparisonSide.MAIN)).thenReturn(null);
        pairedRange(section, 0, 0, 7, 7);
        section.getComparisonRegions().get(0).setMainRegion(null);
        String report = sectionDiff(section, "hidden\n", "hidden\nА = 1;\n");
        assertTrue(report, report.contains("Main: absent"));
        assertTrue(report, report.contains("@@ -0,0 +2 @@"));
        assertTrue(report, report.contains("+А = 1;"));
        assertFalse(report, report.contains("hidden"));
    }

    @Test
    public void partialLinesKeepColumnsAndLiteralNewlineMarkerText() throws Exception
    {
        String before = "prefix А=1; suffix\n";
        String after = "prefix А=2; suffix\n";
        var section = section(BslModuleSectionType.MAIN);
        pairedRange(section, 7, 4, 7, 4);
        String report = sectionDiff(section, before, after);
        assertTrue(report, report.contains("offset 7, length 4, line 1, column 8"));
        assertTrue(report, report.contains("-А=1;\n"));
        assertFalse(report, report.contains("suffix"));
        String literal = "\\ No newline at end of file\n";
        section = section(BslModuleSectionType.MAIN);
        pairedRange(section, 0, literal.length(), 0, literal.length() + 1);
        report = sectionDiff(section, literal, "+" + literal);
        assertTrue(report, report.contains("-" + literal));
        assertTrue(report, report.contains("++" + literal));
    }

    @Test
    public void fragmentsShareOneOutputBudget() throws Exception
    {
        String before = "А = 1;\nБ = 1;\n";
        String after = before.replace("1", "2");
        var section = section(BslModuleSectionType.MAIN);
        pairedRange(section, 0, 7, 0, 7);
        pairedRange(section, 7, 7, 7, 7);
        String report = sectionDiff(section, before, after, 7);
        assertTrue(report, report.contains("truncated"));
        assertFalse(report, report.contains("+Б = 2;"));
    }

    @Test
    public void overlappingOrUnlocatedPresentFragmentsAreUnavailable() throws Exception
    {
        var section = section(BslModuleSectionType.MAIN);
        pairedRange(section, 0, 3, 0, 3);
        pairedRange(section, 1, 3, 1, 3);
        String report = sectionDiff(section, "text\n", "text\n");
        assertTrue(report, report.contains("unavailable"));
        assertFalse(report, report.contains("texts are equal"));
        section.getComparisonRegions().clear();
        section.getComparisonRegions().add(BslCompareFactory.eINSTANCE.createComparisonTextRegion());
        report = sectionDiff(section, "text\n", "text\n");
        assertTrue(report, report.contains("unavailable"));
        assertFalse(report, report.contains("texts are equal"));
    }

    @Test
    public void sectionCoordinatesPreserveBomCrLfAndAncestorSide() throws Exception
    {
        String before = "\ufeff// prefix\r\nА=1;\r\n";
        String after = before.replace("1", "2");
        var section = section(BslModuleSectionType.MAIN);
        pairedRange(section, 12, 6, 12, 6);
        String report = sectionDiff(section, before, after);
        assertTrue(report, report.contains("offset 12, length 6, line 2, column 1"));
        assertTrue(report, report.contains("@@ -2 +2 @@"));
        assertTrue(report, report.contains("-А=1;\r\n+А=2;\r\n"));
        section.getComparisonRegions().get(0).setRegion(ComparisonSide.COMMON_ANCESTOR,
            section.getComparisonRegions().get(0).getMainRegion());
        var ancestor = ComparisonSectionText.capture(ComparisonTextDiff.Source.available("Test.Module", before),
            section, ComparisonSide.COMMON_ANCESTOR);
        assertEquals("А=1;\r\n", ancestor.text());
    }

    @Test
    public void zeroLengthPresentSectionRemainsDistinctFromAbsence() throws Exception
    {
        var section = section(BslModuleSectionType.MAIN);
        pairedRange(section, 0, 0, 0, 0);
        String report = sectionDiff(section, "", "");
        assertTrue(report, report.contains("Main: present"));
        assertTrue(report, report.contains("Other: present"));
        assertTrue(report, report.contains("length 0, line 1, column 1"));
        assertTrue(report, report.contains("selected section texts are equal"));
    }

    private static BslModuleSectionComparisonNode section(BslModuleSectionType type)
    {
        var result = mock(BslModuleSectionComparisonNode.class);
        when(result.getSectionType()).thenReturn(type);
        when(result.getComparisonRegions()).thenReturn(new org.eclipse.emf.common.util.BasicEList<>());
        when(result.getChildren()).thenReturn(new org.eclipse.emf.common.util.BasicEList<>());
        for (var side : new ComparisonSide[] {ComparisonSide.MAIN, ComparisonSide.OTHER, ComparisonSide.COMMON_ANCESTOR})
            when(result.getSymlink(side)).thenReturn("Test.Module.Section");
        return result;
    }

    private static void addRange(BslModuleSectionComparisonNode section, ComparisonSide side, int offset, int length)
    {
        var region = BslCompareFactory.eINSTANCE.createTextRegion();
        region.setOffset(offset);
        region.setLength(length);
        var pair = BslCompareFactory.eINSTANCE.createComparisonTextRegion();
        pair.setRegion(side, region);
        section.getComparisonRegions().add(pair);
    }

    private static void pairedRange(BslModuleSectionComparisonNode section, int left, int leftLength,
        int right, int rightLength)
    {
        addRange(section, ComparisonSide.MAIN, left, leftLength);
        addRange(section, ComparisonSide.OTHER, right, rightLength);
        pairSideRanges(section);
    }

    private static void pairSideRanges(BslModuleSectionComparisonNode section)
    {
        var ranges = section.getComparisonRegions();
        int last = ranges.size() - 1;
        var right = ranges.remove(last).getRegion(ComparisonSide.OTHER);
        ranges.get(last - 1).setRegion(ComparisonSide.OTHER, right);
    }

    private static String sectionDiff(BslModuleSectionComparisonNode section, String before, String after)
        throws Exception
    {
        return sectionDiff(section, before, after, 100);
    }

    private static String sectionDiff(BslModuleSectionComparisonNode section, String before, String after, int maxLines)
        throws Exception
    {
        IComparisonSession session = mock(IComparisonSession.class);
        BslModuleComparisonNode module = mock(BslModuleComparisonNode.class);
        when(section.getParent()).thenReturn(module);
        var converter = mock(IQualifiedNameFilePathConverter.class);
        var view = new ComparisonView(null, session);
        ComparisonTextDiff.Source[] sources = new ComparisonTextDiff.Source[2];
        ComparisonSide[] sides = {ComparisonSide.MAIN, ComparisonSide.OTHER};
        String[] texts = {before, after};
        for (int i = 0; i < sides.length; i++)
        {
            when(module.getSymlink(sides[i])).thenReturn("Test.Module");
            when(converter.getFilePath("Test.Module")).thenReturn("test.bsl");
            var data = mock(IComparisonDataSource.class);
            when(session.getDataSource(sides[i])).thenReturn(data);
            when(data.fileExists(Path.of("test.bsl"))).thenReturn(true);
            when(data.getFileStream(Path.of("test.bsl"))).thenReturn(new ByteArrayInputStream(texts[i].getBytes(StandardCharsets.UTF_8)));
            sources[i] = view.moduleText(section, sides[i], () -> converter);
        }
        return ComparisonTextDiff.render(sources[0], sources[1], "Main", "Other", 1, maxLines);
    }

    /**
     * The handle is not touched by the accessor under test, and passing {@code null} says so: a
     * fake handle would suggest the answer might come from it, which is exactly the confusion this
     * test exists to rule out.
     *
     * @param globalScope what the session answers
     * @return a view over a session scripted to answer that
     */
    private static ComparisonView viewOverSessionSaying(boolean globalScope)
    {
        IComparisonSession session = mock(IComparisonSession.class);
        when(session.isGlobalScope()).thenReturn(globalScope);
        return new ComparisonView(null, session);
    }

    @Test
    public void isGlobalScopeReportsTheSessionsOwnAnswerForAWholeConfigurationRun()
    {
        assertTrue("a whole-configuration run must be reported as one", //$NON-NLS-1$
            viewOverSessionSaying(true).isGlobalScope());
    }

    @Test
    public void isGlobalScopeReportsTheSessionsOwnAnswerForAScopedRun()
    {
        // The direction that costs something when it is wrong: answering true here would make
        // every scoped document claim that content was compared everywhere.
        assertFalse("a scoped run must not be reported as a whole-configuration one", //$NON-NLS-1$
            viewOverSessionSaying(false).isGlobalScope());
    }
}
