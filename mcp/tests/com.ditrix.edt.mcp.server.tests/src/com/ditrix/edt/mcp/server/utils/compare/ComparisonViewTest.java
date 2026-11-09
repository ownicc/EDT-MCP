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
    public void sectionUsesItsWholeOwningModuleAndAbsenceIsNotReadFailure()
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
