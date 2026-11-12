package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;
import org.junit.Assume;
import org.junit.Test;

/** Explicit opt-in: native windows belong on the isolated test display. */
public class BslMethodNavigationUiTest
{
    @Test public void filtersPreserveIdentityAndSelectionRevealsRealEdits()
    {
        Assume.assumeTrue("true".equals(System.getenv("EDT_MCP_NAVIGATION_UI_TESTS")));
        Display display = new Display();
        Shell shell = new Shell(display);
        List<Color> colors = new ArrayList<>();
        try
        {
            shell.setLayout(new FillLayout());
            var left = new StyledText(shell, SWT.MULTI | SWT.V_SCROLL);
            var right = new StyledText(shell, SWT.MULTI | SWT.V_SCROLL);
            left.setText("Function M()\nReturn 1;\nEndFunction\nMessage(1);");
            right.setText("Function M()\nReturn 2;\nEndFunction\nMessage(2);");
            var overview = new BslDiffOverview(left, right, new Canvas(shell, SWT.NONE), rgb -> {
                Color color = new Color(display, rgb); colors.add(color); return color;
            }, () -> false);
            var method = new MethodAwareBslDiff.MethodChange("function:m", "M",
                spans(0, 1, 2), spans(0, 1, 2), MethodAwareBslDiff.MethodKind.MOVED_CHANGED);
            var rows = List.of(new StructuralBslDiff.Row(0, 0), new StructuralBslDiff.Row(1, 1),
                new StructuralBslDiff.Row(2, 2), new StructuralBslDiff.Row(3, 3));
            var diff = new StructuralBslDiff.Result("BSL", "changed", rows, spans(1, 3), spans(1, 3));
            List<MethodAwareBslDiff.MethodChange> opened = new ArrayList<>();
            var panel = new BslMethodNavigationPanel(shell, overview::selectEntry, opened::add);
            overview.onMethodSelection(selected -> panel.select(selected, overview.selectedRow()));
            overview.show(diff, List.of(), List.of(method));
            panel.show(BslMethodNavigation.entries(diff, List.of(method), null));
            shell.setSize(1000, 500); shell.open();
            while (display.readAndDispatch()) { }
            assertEquals(2, panel.table.getItemCount());
            panel.table.setSelection(0); panel.table.notifyListeners(SWT.Selection, new Event());
            assertSame(method, overview.selectedMethod());
            assertEquals(1, right.getLineAtOffset(right.getCaretOffset()));
            panel.table.notifyListeners(SWT.DefaultSelection, new Event());
            assertEquals(List.of(method), opened);
            panel.filter.setText("no match");
            assertEquals(0, panel.table.getItemCount());
            panel.filter.setText("m");
            assertSame(method, ((BslMethodNavigation.Entry)panel.table.getSelection()[0].getData()).method());
            panel.filter.setText("");
            var buttons = ((Composite)panel.root.getChildren()[2]).getChildren();
            for (Control control : buttons)
            {
                Button button = (Button)control;
                button.setSelection(false); button.notifyListeners(SWT.Selection, new Event());
            }
            assertEquals(0, panel.table.getItemCount());
            Button moved = (Button)buttons[1];
            moved.setSelection(true); moved.notifyListeners(SWT.Selection, new Event());
            assertEquals(1, panel.table.getItemCount());
            assertSame(method, ((BslMethodNavigation.Entry)panel.table.getItem(0).getData()).method());
            Button changed = (Button)buttons[0];
            changed.setSelection(true); changed.notifyListeners(SWT.Selection, new Event());
            assertEquals(2, panel.table.getItemCount());
            right.setSelection(right.getOffsetAtLine(3));
            assertNull(overview.selectedMethod());
            assertNull(((BslMethodNavigation.Entry)panel.table.getSelection()[0].getData()).method());
            panel.table.notifyListeners(SWT.DefaultSelection, new Event());
            assertEquals(1, opened.size());
            Color background = new Color(display, new RGB(30, 30, 30)); colors.add(background);
            Color foreground = new Color(display, new RGB(212, 212, 212)); colors.add(foreground);
            panel.applyTheme(background, foreground);
            assertEquals(background, panel.table.getBackground());
            assertEquals(foreground, panel.filter.getForeground());
            panel.show(BslMethodNavigation.entries(diff, List.of(), "ambiguous-identity:flat-tokens"));
            assertEquals(1, panel.table.getItemCount());
            panel.table.setSelection(0); panel.table.notifyListeners(SWT.Selection, new Event());
            panel.table.notifyListeners(SWT.DefaultSelection, new Event());
            assertEquals(1, opened.size());
        }
        finally
        {
            shell.dispose(); colors.forEach(Color::dispose); display.dispose();
        }
    }

    private static List<StructuralBslDiff.Span> spans(int... lines)
    {
        List<StructuralBslDiff.Span> result = new ArrayList<>();
        for (int line : lines) result.add(new StructuralBslDiff.Span(line, 0, 1));
        return result;
    }
}
