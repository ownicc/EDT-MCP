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
            assertEquals("Status icons must have their own column", 3, panel.table.getColumnCount());
            assertEquals("M", panel.table.getItem(0).getText(1));
            panel.table.setSelection(0); panel.table.notifyListeners(SWT.Selection, new Event());
            assertSame(method, overview.selectedMethod());
            assertEquals(1, right.getLineAtOffset(right.getCaretOffset()));
            var selectedImage = new org.eclipse.swt.graphics.Image(display, 300, 80);
            var selectedGc = new org.eclipse.swt.graphics.GC(selectedImage);
            try
            {
                Color nativeSelectionText = display.getSystemColor(SWT.COLOR_BLACK);
                selectedGc.setForeground(nativeSelectionText);
                Event paint = new Event(); paint.index = 0; paint.item = panel.table.getItem(0); paint.gc = selectedGc;
                panel.table.notifyListeners(SWT.PaintItem, paint);
                assertEquals("Move icon must respect native inactive selection text", nativeSelectionText, selectedGc.getForeground());
            }
            finally { selectedGc.dispose(); selectedImage.dispose(); }
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
                assertTrue("Type filters use toggle buttons", (button.getStyle() & SWT.TOGGLE) != 0);
            }
            var actions = (Composite)panel.root.getChildren()[3];
            Button selectAll = (Button)actions.getChildren()[0];
            Button clearAll = (Button)actions.getChildren()[1];
            clearAll.notifyListeners(SWT.Selection, new Event());
            for (Control control : buttons) assertFalse(((Button)control).getSelection());
            assertEquals(0, panel.table.getItemCount());
            Button moved = (Button)buttons[1];
            moved.setSelection(true); moved.notifyListeners(SWT.Selection, new Event());
            assertEquals(1, panel.table.getItemCount());
            assertSame(method, ((BslMethodNavigation.Entry)panel.table.getItem(0).getData()).method());
            Button changed = (Button)buttons[0];
            changed.setSelection(true); changed.notifyListeners(SWT.Selection, new Event());
            assertEquals(2, panel.table.getItemCount());
            selectAll.notifyListeners(SWT.Selection, new Event());
            for (Control control : buttons) assertTrue(((Button)control).getSelection());
            assertEquals(2, panel.table.getItemCount());
            right.setSelection(right.getOffsetAtLine(3));
            assertNull(overview.selectedMethod());
            assertNull(((BslMethodNavigation.Entry)panel.table.getSelection()[0].getData()).method());
            panel.table.notifyListeners(SWT.DefaultSelection, new Event());
            assertEquals(1, opened.size());
            Color background = new Color(display, new RGB(30, 30, 30)); colors.add(background);
            Color foreground = new Color(display, new RGB(212, 212, 212)); colors.add(foreground);
            Color nativeButtonForeground = moved.getForeground();
            panel.applyTheme(background, foreground);
            assertEquals(background, panel.table.getBackground());
            assertEquals(foreground, panel.filter.getForeground());
            assertEquals("Toggle labels must retain their native theme contrast", nativeButtonForeground, moved.getForeground());
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

    @Test public void oneSidedMethodsRemainNavigableWithoutOpeningComparison()
    {
        Assume.assumeTrue("true".equals(System.getenv("EDT_MCP_NAVIGATION_UI_TESTS")));
        Display display = new Display(); Shell shell = new Shell(display);
        try
        {
            shell.setLayout(new FillLayout());
            List<BslMethodNavigation.Entry> revealed = new ArrayList<>();
            List<MethodAwareBslDiff.MethodChange> opened = new ArrayList<>();
            var panel = new BslMethodNavigationPanel(shell, revealed::add, opened::add);
            shell.setSize(320, 400); shell.open();
            for (boolean added : List.of(true, false))
            {
                var method = new MethodAwareBslDiff.MethodChange("method:one", "One",
                    added ? List.of() : spans(0), added ? spans(0) : List.of(),
                    added ? MethodAwareBslDiff.MethodKind.ADDED : MethodAwareBslDiff.MethodKind.REMOVED);
                var entry = new BslMethodNavigation.Entry(method, 0, java.util.Set.of(0), 1, 1, null);
                panel.show(List.of(entry)); panel.table.setSelection(0);
                panel.table.notifyListeners(SWT.Selection, new Event());
                assertSame(entry, revealed.get(revealed.size() - 1));
                panel.table.notifyListeners(SWT.DefaultSelection, new Event());
                assertTrue("A method without a peer must not open on double-click", opened.isEmpty());
                Event enter = new Event(); enter.keyCode = SWT.CR;
                panel.filter.notifyListeners(SWT.KeyDown, enter);
                assertTrue("A method without a peer must not open on Enter", opened.isEmpty());
            }
        }
        finally { shell.dispose(); display.dispose(); }
    }

    @Test public void nonMoveStatusIconsUseChosenSizeEvenWithLargeRows()
    {
        Assume.assumeTrue("true".equals(System.getenv("EDT_MCP_NAVIGATION_UI_TESTS")));
        Display display = new Display(); Shell shell = new Shell(display);
        var largeFont = new org.eclipse.swt.graphics.Font(display, "Sans", 18, SWT.NORMAL);
        try
        {
            shell.setLayout(new FillLayout());
            var panel = new BslMethodNavigationPanel(shell, entry -> { }, method -> { });
            panel.table.setFont(largeFont);
            shell.setSize(320, 400); shell.open();
            for (var kind : List.of(MethodAwareBslDiff.MethodKind.ADDED,
                MethodAwareBslDiff.MethodKind.REMOVED, MethodAwareBslDiff.MethodKind.CHANGED))
            {
                var method = new MethodAwareBslDiff.MethodChange("method:icon", "Icon", spans(0), spans(0), kind);
                panel.show(List.of(new BslMethodNavigation.Entry(method, 0, java.util.Set.of(0), 1, 1, null)));
                shell.layout(true, true); while (display.readAndDispatch()) { }
                var image = new org.eclipse.swt.graphics.Image(display, 80, 60);
                var gc = new org.eclipse.swt.graphics.GC(image);
                try
                {
                    gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE)); gc.fillRectangle(image.getBounds());
                    gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
                    Event paint = new Event(); paint.index = 0; paint.item = panel.table.getItem(0); paint.gc = gc;
                    panel.table.notifyListeners(SWT.PaintItem, paint);
                    var pixels = image.getImageData(); int minX = 80, maxX = -1, minY = 60, maxY = -1;
                    for (int y = 0; y < 60; y++) for (int x = 0; x < 80; x++)
                    {
                        RGB pixel = pixels.palette.getRGB(pixels.getPixel(x, y));
                        if (pixel.red + pixel.green + pixel.blue < 384)
                        {
                            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                        }
                    }
                    assertTrue(kind + " icon is too narrow", maxX - minX + 1 >= 14);
                    assertTrue(kind + " icon exceeds the chosen 16 px size", maxX - minX + 1 <= 16);
                    if (kind != MethodAwareBslDiff.MethodKind.REMOVED)
                        assertTrue(kind + " icon is too short", maxY - minY + 1 >= 5);
                }
                finally { gc.dispose(); image.dispose(); }
            }
        }
        finally { shell.dispose(); largeFont.dispose(); display.dispose(); }
    }

    @Test public void largerStatusIconsKeepCountsInsideTheTable()
    {
        Assume.assumeTrue("true".equals(System.getenv("EDT_MCP_NAVIGATION_UI_TESTS")));
        Display display = new Display(); Shell shell = new Shell(display);
        try
        {
            shell.setLayout(new FillLayout());
            var panel = new BslMethodNavigationPanel(shell, entry -> { }, method -> { });
            panel.show(List.of(new BslMethodNavigation.Entry(null, 0, java.util.Set.of(0), 1, 1, null)));
            shell.setSize(320, 400); shell.open(); shell.layout(true, true);
            while (display.readAndDispatch()) { }
            Event tallerRows = new Event(); tallerRows.index = 0; tallerRows.height = 44;
            panel.table.notifyListeners(SWT.MeasureItem, tallerRows);
            int totalWidth = 0;
            for (TableColumn column : panel.table.getColumns()) totalWidth += column.getWidth();
            assertTrue("Larger icons must not clip the counts column", totalWidth <= panel.table.getClientArea().width);
        }
        finally { shell.dispose(); display.dispose(); }
    }

    @Test public void moveIconHasReadableFontIndependentGeometry()
    {
        Assume.assumeTrue("true".equals(System.getenv("EDT_MCP_NAVIGATION_UI_TESTS")));
        assertTrue(BslMoveIcon.size(18) >= 16);
        assertTrue(BslMoveIcon.size(44) > BslMoveIcon.size(22));
        Display display = new Display();
        try
        {
            for (int lineHeight : List.of(18, 28, 44))
            {
                int size = BslMoveIcon.size(lineHeight);
                var image = new org.eclipse.swt.graphics.Image(display, size, size);
                var gc = new org.eclipse.swt.graphics.GC(image);
                try
                {
                    gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
                    gc.fillRectangle(image.getBounds());
                    gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
                    BslMoveIcon.draw(gc, 0, 0, size);
                    var pixels = image.getImageData();
                    int minX = size, maxX = -1, minY = size, maxY = -1;
                    for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
                    {
                        RGB pixel = pixels.palette.getRGB(pixels.getPixel(x, y));
                        if (pixel.red + pixel.green + pixel.blue < 384)
                        {
                            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                        }
                    }
                    assertTrue("Arrow fills its icon width", maxX - minX + 1 >= size - 4);
                    assertTrue("Arrowheads are visible", maxY - minY + 1 >= size / 2);
                }
                finally { gc.dispose(); image.dispose(); }
            }
        }
        finally { display.dispose(); }
    }

    private static List<StructuralBslDiff.Span> spans(int... lines)
    {
        List<StructuralBslDiff.Span> result = new ArrayList<>();
        for (int line : lines) result.add(new StructuralBslDiff.Span(line, 0, 1));
        return result;
    }
}
