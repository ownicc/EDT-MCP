package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.Test;

public class StructuralBslDiffTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void processUsesDetachedFilesAndCleansThemAfterReading() throws Exception
    {
        Path engine = engine("printf '%s' '{\"language\":\"BSL\",\"status\":\"unchanged\"}'\n");
        var result = StructuralBslDiff.compare(engine, "A(1);\n", "A( 1 );\n", new NullProgressMonitor());
        assertEquals("unchanged", result.status());
        Path input = Path.of(Files.readString(Path.of(engine+".input")));
        assertFalse("Snapshot files must be deleted", Files.exists(input.getParent()));
    }

    @Test public void cancellationKillsTheEngineAndCleansItsSnapshots() throws Exception
    {
        Path engine = engine("while :; do :; done\n");
        var monitor = new NullProgressMonitor() {
            @Override public boolean isCanceled() { return Files.exists(Path.of(engine+".input")); }
        };
        assertThrows(OperationCanceledException.class,
            () -> StructuralBslDiff.compare(engine, "A", "B", monitor));
        Path input = Path.of(Files.readString(Path.of(engine+".input")));
        assertFalse(Files.exists(input.getParent()));
        long pid = Long.parseLong(Files.readString(Path.of(engine+".pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private Path engine(String body) throws Exception
    {
        org.junit.Assume.assumeTrue("POSIX process checks", Files.isExecutable(Path.of("/bin/sh")));
        Path engine = temporary.newFile("difft").toPath().toAbsolutePath();
        Files.writeString(engine, "#!/bin/sh\nprintf '%s' \"$3\" > \"$0.input\"\n"
            + "printf '%s' \"$$\" > \"$0.pid\"\n" + body);
        assertTrue(engine.toFile().setExecutable(true));
        return engine;
    }

    @Test public void unorderedChunksUseUtf16ColumnsAndPreserveAlignedRows()
    {
        String json = """
            {"language":"BSL","status":"changed",
             "aligned_lines":[[0,0],[1,1],[null,2],[2,3]],
             "chunks":[[{"rhs":{"line_number":2,"changes":[
                 {"start":7,"end":10,"content":"120"}]}},
               {"lhs":{"line_number":1,"changes":[
                 {"start":7,"end":10,"content":"100"}]}}]]}
            """;
        var result = StructuralBslDiff.parse(json, "A\r\nЯ😀 100\r\nZ\r\n",
            "A\nЯ😀\nЯ😀 120\nZ\n");
        assertEquals(List.of(new StructuralBslDiff.Span(1, 4, 3)), result.left());
        assertEquals(List.of(new StructuralBslDiff.Span(2, 4, 3)), result.right());
        assertEquals(new StructuralBslDiff.Row(null, 2), result.rows().get(2));
        assertEquals(new StructuralBslDiff.Row(2, 3), result.rows().get(3));
    }

    @Test public void formattingStillShowsBothCompleteSources()
    {
        var result = StructuralBslDiff.parse(
            "{\"language\":\"BSL\",\"status\":\"unchanged\"}",
            "A(1);\n", "A(\n  1\n);\n");
        assertTrue(result.left().isEmpty());
        assertTrue(result.right().isEmpty());
        assertEquals(List.of(new StructuralBslDiff.Row(0,0), new StructuralBslDiff.Row(1,1),
            new StructuralBslDiff.Row(null,2), new StructuralBslDiff.Row(null,3)), result.rows());
    }

    @Test public void creationAndDeletionHighlightTheEntireExistingSide()
    {
        var created = StructuralBslDiff.parse(
            "{\"language\":\"BSL\",\"status\":\"created\"}", "", "Я😀\n");
        assertEquals(List.of(new StructuralBslDiff.Span(0,0,3)), created.right());
        assertEquals(List.of(new StructuralBslDiff.Row(null,0), new StructuralBslDiff.Row(null,1)), created.rows());
        var deleted = StructuralBslDiff.parse(
            "{\"language\":\"BSL\",\"status\":\"deleted\"}", "Я😀\n", "");
        assertEquals(List.of(new StructuralBslDiff.Span(0,0,3)), deleted.left());
    }

    @Test public void partialAlignmentIncludesEveryUnreportedSourceLine()
    {
        var result = StructuralBslDiff.parse("""
            {"language":"BSL","status":"changed","chunks":[],"aligned_lines":[[1,2]]}
            """, "A\nB\nC\n", "A\nX\nB\nC\n");
        assertEquals(List.of(new StructuralBslDiff.Row(0,0),
            new StructuralBslDiff.Row(null,1), new StructuralBslDiff.Row(1,2),
            new StructuralBslDiff.Row(2,3), new StructuralBslDiff.Row(3,4)), result.rows());
    }

    @Test public void terminalNewlineHasAnAddressableEmptyLine()
    {
        var result = StructuralBslDiff.parse("""
            {"language":"BSL","status":"changed","chunks":[],"aligned_lines":[[0,0],[1,1]]}
            """, "A\n", "B\n");
        assertEquals(List.of(new StructuralBslDiff.Row(0,0), new StructuralBslDiff.Row(1,1)), result.rows());
    }

    @Test public void finalAlignmentMayContainAVirtualEndOfFileLine()
    {
        String json = """
            {"language":"BSL","status":"changed","chunks":[],"aligned_lines":[[0,0],[1,1]]}
            """;
        assertEquals(List.of(new StructuralBslDiff.Row(0,0)), StructuralBslDiff.parse(json,"A", "B").rows());
        assertEquals(List.of(new StructuralBslDiff.Row(0,0), new StructuralBslDiff.Row(1,null)),
            StructuralBslDiff.parse(json,"A\n", "B").rows());
    }

    @Test public void refusesInvalidUtf8BoundaryOrMismatchedSource()
    {
        for (String span : List.of(
            "{\"start\":1,\"end\":2,\"content\":\"Я\"}",
            "{\"start\":0,\"end\":2,\"content\":\"X\"}",
            "{\"start\":0,\"end\":99,\"content\":\"Я\"}"))
        {
            String json = "{\"language\":\"BSL\",\"status\":\"changed\",\"chunks\":[["
                + "{\"lhs\":{\"line_number\":0,\"changes\":["+span+"]}}]]}";
            assertThrows(IllegalArgumentException.class, () -> StructuralBslDiff.parse(json,"Я", "X"));
        }
    }

    @Test public void refusesMalformedOrNonmonotonicAlignment()
    {
        for (String alignment : List.of("[[0,0],[0,1]]", "[[99,0]]", "[[null,null]]"))
        {
            String json="{\"language\":\"BSL\",\"status\":\"changed\",\"chunks\":[],\"aligned_lines\":"+alignment+"}";
            assertThrows(IllegalArgumentException.class, () -> StructuralBslDiff.parse(json,"A\nB", "A\nB"));
        }
    }
}
