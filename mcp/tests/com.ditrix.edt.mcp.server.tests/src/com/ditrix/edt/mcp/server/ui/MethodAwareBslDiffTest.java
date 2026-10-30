package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

public class MethodAwareBslDiffTest
{
    @Test public void tokenDiffIsolatesOneRepeatedCallArgument()
    {
        var engine = new MethodAwareBslDiff(null, new NullProgressMonitor());
        var a = new ArrayList<MethodAwareBslDiff.Token>();
        var b = new ArrayList<MethodAwareBslDiff.Token>();
        for (int i = 0; i < 300; i++)
        {
            a.add(new MethodAwareBslDiff.Token("number", "1", i, 1));
            b.add(new MethodAwareBslDiff.Token("number", i == 150 ? "2" : "1", i, 1));
        }
        var left = new ArrayList<MethodAwareBslDiff.Token>();
        var right = new ArrayList<MethodAwareBslDiff.Token>();
        engine.diff(a, b, left, right);
        assertEquals(List.of(new MethodAwareBslDiff.Token("number", "1", 150, 1)), left);
        assertEquals(List.of(new MethodAwareBslDiff.Token("number", "2", 150, 1)), right);
    }

    @Test public void spansUseUtf16AndSplitEveryPhysicalLineDelimiter()
    {
        String source = "\ufeffA😀\r\n\tB\rC\n";
        var spans = MethodAwareBslDiff.spans(source,
            List.of(new MethodAwareBslDiff.Token("text", "😀\r\n\tB\rC", 2, 8)));
        assertEquals(List.of(new StructuralBslDiff.Span(0, 2, 2),
            new StructuralBslDiff.Span(1, 0, 2), new StructuralBslDiff.Span(2, 0, 1)), spans);
    }

    @Test public void newlineOnlyChangeStillMarksItsLine()
    {
        assertEquals(List.of(new StructuralBslDiff.Span(0, 1, 0)), MethodAwareBslDiff.spans("a\n",
            List.of(new MethodAwareBslDiff.Token("line", "\n", 1, 1))));
    }

    @Test public void alignmentIncludesFinalEmptyLineAndNeverDropsSource()
    {
        var engine = new MethodAwareBslDiff(null, new NullProgressMonitor());
        assertEquals(List.of(new StructuralBslDiff.Row(0, 0), new StructuralBslDiff.Row(1, 1),
            new StructuralBslDiff.Row(null, 2)), engine.align("a\r\nb", "a\r\nc\r\n"));
        assertEquals(List.of(new StructuralBslDiff.Row(null, 0), new StructuralBslDiff.Row(null, 1)), engine.align("", "\n"));
        assertEquals(List.of(), engine.align("", ""));
    }

    @Test public void refusesOversizedInputBeforeEnteringParser()
    {
        var engine = new MethodAwareBslDiff(null, new NullProgressMonitor());
        assertThrows(IllegalArgumentException.class, () -> engine.compare("x".repeat(524289), ""));
        assertThrows(IllegalArgumentException.class, () -> engine.compare("\n".repeat(20000), ""));
    }

    @Test public void conditionalDirectiveAfterCrRegionForcesFlatFallback()
    {
        var engine = new MethodAwareBslDiff(null, new NullProgressMonitor());
        var parsed = new MethodAwareBslDiff.Parsed(org.mockito.Mockito.mock(org.eclipse.xtext.parser.IParseResult.class),
            List.of(), 0, List.of());
        assertEquals("preprocessor:flat-tokens", engine.project(parsed,
            "#Region A\r#If Client Then\r#EndIf\r#EndRegion").fallback());
    }

    @Test public void observesCancellationBeforeEnteringParser()
    {
        var monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        assertThrows(OperationCanceledException.class, () -> new MethodAwareBslDiff(null, monitor).compare("a", "b"));
    }
}
