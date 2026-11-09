/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.accessibility.AccessibleValueAdapter;
import org.eclipse.swt.accessibility.AccessibleValueEvent;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;

import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Result;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Span;

/** One scrollbar for aligned read-only panes; all positions are display rows, never source offsets. */
final class BslDiffOverview
{
    private static final String MOVE_ICON = "↔"; //$NON-NLS-1$
    // Red/green flags can coexist in an edited hunk. A move has two linked endpoints.
    private record Change(int start, int end, int kind, String id, boolean left, int peer,
        int sourceLine, int peerLine, int badgeRow) { }
    private record Hit(Rectangle area, Change change) { }
    private final StyledText left;
    private final StyledText right;
    private final Canvas bar;
    private final int baseGutter;
    private final Function<RGB, Color> colors;
    private final BooleanSupplier dark;
    private final List<Change> changes = new ArrayList<>();
    private final List<Hit> leftHits = new ArrayList<>(), rightHits = new ArrayList<>();
    private Change selected;
    private final Map<String, MethodAwareBslDiff.Move> moveByLabel = new HashMap<>();
    private java.util.function.Consumer<MethodAwareBslDiff.Move> moveSelection = move -> { };
    private int dragOffset = -1;
    private boolean syncing;

    BslDiffOverview(StyledText left, StyledText right, Canvas bar, Function<RGB, Color> colors,
        BooleanSupplier dark)
    {
        this.left = left;
        this.right = right;
        this.bar = bar;
        this.baseGutter = Math.max(left.getLeftMargin(), right.getLeftMargin());
        this.colors = colors;
        this.dark = dark;
        bar.setToolTipText(Messages.BslDiff_Overview);
        bar.addPaintListener(event -> paint(event.gc));
        bar.addListener(SWT.MouseDown, event -> {
            if (event.button != 1) return;
            bar.setFocus();
            // Marks remain clickable along the edges. The centre of the thumb is the drag handle.
            if (event.x < bar.getClientArea().width / 3 || event.x >= bar.getClientArea().width * 2 / 3)
            {
                for (int i = changes.size() - 1; i >= 0; i--)
                    if (markerArea(changes.get(i)).contains(event.x, event.y)) { select(changes.get(i)); return; }
            }
            Rectangle thumb = thumb();
            if (thumb.contains(event.x, event.y)) dragOffset = event.y - thumb.y;
            else scrollTo(trackOffset(event.y - thumb.height / 2));
        });
        bar.addListener(SWT.MouseMove, event -> {
            if (dragOffset >= 0) scrollTo(trackOffset(event.y - dragOffset));
        });
        bar.addListener(SWT.MouseUp, event -> dragOffset = -1);
        bar.addListener(SWT.FocusOut, event -> { dragOffset = -1; bar.redraw(); });
        bar.addListener(SWT.FocusIn, event -> bar.redraw());
        bar.addListener(SWT.MouseWheel, event -> { event.doit = false; wheel(event.count); });
        bar.addListener(SWT.KeyDown, event -> {
            int page = Math.max(right.getLineHeight(), right.getClientArea().height - right.getLineHeight());
            int position = right.getTopPixel();
            switch (event.keyCode)
            {
                case SWT.HOME: position = 0; break;
                case SWT.END: position = maximum(); break;
                case SWT.ARROW_UP: position -= right.getLineHeight(); break;
                case SWT.ARROW_DOWN: position += right.getLineHeight(); break;
                case SWT.PAGE_UP: position -= page; break;
                case SWT.PAGE_DOWN: position += page; break;
                default: return;
            }
            event.doit = false;
            scrollTo(position);
        });
        bar.getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override public void getName(AccessibleEvent event) { event.result = Messages.BslDiff_Overview; }
        });
        bar.getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
            @Override public void getRole(AccessibleControlEvent event) { event.detail = ACC.ROLE_SCROLLBAR; }
        });
        bar.getAccessible().addAccessibleValueListener(new AccessibleValueAdapter() {
            @Override public void getCurrentValue(AccessibleValueEvent event) { event.value = right.getTopPixel(); }
            @Override public void getMinimumValue(AccessibleValueEvent event) { event.value = 0; }
            @Override public void getMaximumValue(AccessibleValueEvent event) { event.value = maximum(); }
            @Override public void setCurrentValue(AccessibleValueEvent event) { if (event.value != null) scrollTo(event.value.intValue()); }
        });
        for (boolean lhs : List.of(true, false))
        {
            StyledText text = lhs ? left : right;
            // Retain StyledText's native keyboard/selection scrolling, but replace both visible bars.
            text.getVerticalBar().setVisible(false);
            text.addListener(SWT.MouseWheel, event -> { event.doit = false; wheel(event.count); });
            text.addListener(SWT.KeyUp, event -> scrollTo(text.getTopPixel()));
            text.addListener(SWT.MouseUp, event -> scrollTo(text.getTopPixel()));
            text.getVerticalBar().addListener(SWT.Selection, event -> scrollTo(text.getTopPixel()));
            text.addListener(SWT.Paint, event -> {
                // Also catches native selection autoscroll and caret movement not initiated by a key event.
                if (!syncing && left.getTopPixel() != right.getTopPixel()) scrollTo(text.getTopPixel());
                paintMoves(event.gc, lhs);
                bar.redraw();
            });
            text.addListener(SWT.Resize, event -> {
                text.getDisplay().asyncExec(() -> { if (!bar.isDisposed()) scrollTo(right.getTopPixel()); });
            });
            text.addListener(SWT.MouseMove, event -> {
                String tip = null;
                for (Hit hit : lhs ? leftHits : rightHits)
                    if (hit.area.contains(event.x, event.y)) { tip = moveLabel(hit.change); break; }
                if (!java.util.Objects.equals(tip, text.getToolTipText())) text.setToolTipText(tip);
            });
            text.addListener(SWT.MouseDown, event -> {
                if (event.button != 1) return;
                for (Hit hit : lhs ? leftHits : rightHits)
                    if (hit.area.contains(event.x, event.y)) {
                        Change peer = changes.stream().filter(c -> c.kind == 4 && c.id.equals(hit.change.id)
                            && c.left != hit.change.left).findFirst().orElse(hit.change);
                        select(peer); event.doit = false; break;
                    }
            });
        }
    }

    void show(Result result, List<MethodAwareBslDiff.Move> moves)
    {
        changes.clear();
        selected = null;
        moveByLabel.clear();
        moveSelection.accept(null);
        Map<Integer, Integer> a = new HashMap<>(), b = new HashMap<>();
        for (int i = 0; i < result.rows().size(); i++)
        {
            var row = result.rows().get(i);
            if (row.left() != null) a.put(row.left(), i);
            if (row.right() != null) b.put(row.right(), i);
        }
        int[] kinds = new int[result.rows().size()];
        for (Span span : result.left()) kinds[a.get(span.line())] |= 1;
        for (Span span : result.right()) kinds[b.get(span.line())] |= 2;
        int id = 1;
        for (var move : moves)
        {
            if (move.left().isEmpty() || move.right().isEmpty()) continue;
            int x = move.left().get(0).line(), y = move.right().get(0).line();
            int xHeader = declarationLine(move.left(), a, left), yHeader = declarationLine(move.right(), b, right);
            String label = "M" + id++; //$NON-NLS-1$
            moveByLabel.put(label, move);
            changes.add(new Change(a.get(x), a.get(move.left().get(move.left().size() - 1).line()) + 1,
                4, label, true, b.get(yHeader), xHeader, yHeader, a.get(xHeader)));
            changes.add(new Change(b.get(y), b.get(move.right().get(move.right().size() - 1).line()) + 1,
                4, label, false, a.get(xHeader), yHeader, xHeader, b.get(yHeader)));
        }
        for (int i = 0; i < kinds.length;)
        {
            if (kinds[i] == 0) { i++; continue; }
            int start = i, kind = 0;
            while (i < kinds.length && kinds[i] != 0) kind |= kinds[i++];
            changes.add(new Change(start, i, kind, "", false, -1, -1, -1, -1)); //$NON-NLS-1$
        }
        changes.sort(Comparator.comparingInt(Change::start));
        left.setLeftMargin(baseGutter);
        right.setLeftMargin(baseGutter);
        redraw();
    }

    boolean hasChanges() { return !changes.isEmpty(); }

    void navigate(boolean forward)
    {
        if (changes.isEmpty()) return;
        int index = changes.indexOf(selected);
        if (index < 0 && !forward) index = 0;
        select(changes.get(Math.floorMod(index + (forward ? 1 : -1), changes.size())));
    }

    private void select(Change change)
    {
        selected = change;
        moveSelection.accept(selectedMove());
        jump(change.kind == 4 ? change.badgeRow : change.start);
    }

    void onMoveSelection(java.util.function.Consumer<MethodAwareBslDiff.Move> listener)
    {
        moveSelection = listener;
        listener.accept(selectedMove());
    }

    MethodAwareBslDiff.Move selectedMove()
    {
        if (selected == null) return null;
        if (!selected.id.isEmpty()) return moveByLabel.get(selected.id);
        for (Change change : changes)
            if (change.kind == 4 && change.start <= selected.start && selected.start < change.end)
                return moveByLabel.get(change.id);
        return null;
    }

    private void jump(int row)
    {
        for (StyledText text : List.of(left, right))
            text.setSelection(text.getOffsetAtLine(Math.min(text.getLineCount() - 1, row)));
        revealLine(row);
    }

    void revealLine(int row) { scrollTo(Math.max(0, row - 3) * right.getLineHeight()); }

    private int maximum()
    {
        return Math.max(0, right.getLineCount() * right.getLineHeight() + right.getTopMargin()
            + right.getBottomMargin() - right.getClientArea().height);
    }

    private Rectangle thumb()
    {
        var area = bar.getClientArea();
        int maximum = maximum();
        int height = maximum == 0 ? area.height : Math.min(area.height, Math.max(24,
            (int)((double)area.height * right.getClientArea().height / (maximum + right.getClientArea().height))));
        int y = maximum == 0 ? 0 : (int)((double)right.getTopPixel() / maximum * (area.height - height));
        return new Rectangle(1, Math.max(0, y), Math.max(0, area.width - 2), height);
    }

    private int trackOffset(int y)
    {
        return (int)Math.round((double)y / Math.max(1, bar.getClientArea().height - thumb().height) * maximum());
    }

    private void wheel(int count) { scrollTo(right.getTopPixel() - count * right.getLineHeight() * 3); }

    private void scrollTo(int pixel)
    {
        if (syncing || bar.isDisposed()) return;
        syncing = true;
        try
        {
            right.setTopPixel(Math.max(0, Math.min(maximum(), pixel)));
            left.setTopPixel(right.getTopPixel());
            // A horizontal scrollbar or resize can temporarily leave different viewport heights.
            right.setTopPixel(left.getTopPixel());
        }
        finally { syncing = false; }
        redraw();
        bar.getAccessible().sendEvent(ACC.EVENT_VALUE_CHANGED, null);
    }

    void redraw()
    {
        if (bar.isDisposed()) return;
        left.redraw();
        right.redraw();
        bar.redraw();
    }

    private Color color(RGB light, RGB darkColor) { return colors.apply(dark.getAsBoolean() ? darkColor : light); }
    private Color blue() { return color(new RGB(47, 107, 191), new RGB(138, 188, 255)); }
    private Color red() { return color(new RGB(191, 65, 65), new RGB(255, 132, 132)); }
    private Color green() { return color(new RGB(44, 142, 78), new RGB(112, 230, 137)); }
    private Color background() { return color(new RGB(246, 248, 251), new RGB(37, 39, 44)); }
    private Rectangle markerArea(Change change)
    {
        var area = bar.getClientArea();
        int y = Math.min(Math.max(0, area.height - 3),
            (int)((double)change.start / Math.max(1, right.getLineCount()) * area.height));
        int height = Math.max(3,
            (int)((double)(change.end - change.start) / Math.max(1, right.getLineCount()) * area.height));
        return new Rectangle(2, y, Math.max(1, area.width - 4), Math.min(height, Math.max(0, area.height - y)));
    }

    private void paint(GC gc)
    {
        var area = bar.getClientArea();
        gc.setBackground(background());
        gc.fillRectangle(area);
        Rectangle thumb = thumb();
        gc.setBackground(color(new RGB(213, 219, 229), new RGB(67, 73, 83)));
        gc.fillRoundRectangle(thumb.x, thumb.y, thumb.width, thumb.height, 5, 5);
        for (Change change : changes)
        {
            Rectangle mark = markerArea(change);
            int y = mark.y, height = mark.height, width = mark.width;
            gc.setBackground(change.kind == 4 ? blue() : change.kind == 1 ? red() : green());
            gc.fillRectangle(2, y, width, height);
            if (change.kind == 3)
            {
                gc.setBackground(red());
                gc.fillRectangle(2, y, width, Math.max(1, height / 2));
            }
            if (change == selected || (!change.id.isEmpty() && selected != null && change.id.equals(selected.id)))
            {
                gc.setForeground(blue());
                gc.drawRectangle(0, y - 1, Math.max(0, area.width - 1), height + 1);
            }
        }
        gc.setForeground(color(new RGB(104, 116, 136), new RGB(173, 184, 204)));
        gc.drawRoundRectangle(thumb.x, thumb.y, Math.max(0, thumb.width - 1), Math.max(0, thumb.height - 1), 5, 5);
        if (bar.isFocusControl()) gc.drawFocus(0, 0, area.width, area.height);
    }

    private static final java.util.regex.Pattern DECLARATION = java.util.regex.Pattern.compile(
        "(?iu)^\\s*(?:Функция|Процедура|Function|Procedure)(?=\\s|$)");

    private static int declarationLine(List<Span> spans, Map<Integer, Integer> rows, StyledText text)
    {
        for (Span span : spans)
            if (DECLARATION.matcher(text.getLine(rows.get(span.line()))).find()) return span.line();
        return spans.get(0).line();
    }

    private String moveLabel(Change change)
    {
        String key = moveByLabel.get(change.id).key();
        String name = key.substring(key.indexOf(':') + 1);
        return org.eclipse.osgi.util.NLS.bind(Messages.BslDiff_MoveJump, new Object[] {
            name, change.left ? Messages.BslReview_Proposed : Messages.BslReview_Current, change.peerLine + 1 });
    }

    private void paintMoves(GC gc, boolean lhs)
    {
        StyledText text = lhs ? left : right;
        List<Hit> hits = lhs ? leftHits : rightHits;
        hits.clear();
        gc.setFont(text.getFont());
        for (Change change : changes)
        {
            if (change.kind != 4 || change.left != lhs) continue;
            int y = text.getLinePixel(change.badgeRow);
            if (y < 0 || y >= text.getClientArea().height) continue;
            // A dedicated gutter target stays visible even for long declarations or horizontal scrolling.
            String label = MOVE_ICON;
            int width = gc.stringExtent(label).x + 8;
            int numberX = text.getLeftMargin() - 7 - gc.stringExtent(Integer.toString(change.sourceLine + 1)).x;
            int gap = gc.stringExtent(" ").x; //$NON-NLS-1$
            int x = Math.max(3, numberX - gap - width);
            int height = text.getLineHeight();
            // The declaration's move action occupies the status column; retain the source number.
            gc.setBackground(text.getBackground());
            gc.fillRectangle(0, y, Math.max(0, numberX - gap), height);
            gc.setBackground(background());
            gc.fillRoundRectangle(x, y, width, height, 5, 5);
            gc.setForeground(blue());
            gc.drawRoundRectangle(x, y, width, height - 1, 5, 5);
            gc.drawString(label, x + 4, y, true);
            String hint = (lhs ? " → " : " ← ") + (change.peerLine + 1); //$NON-NLS-1$ //$NON-NLS-2$
            int end = text.getOffsetAtLine(change.badgeRow) + text.getLine(change.badgeRow).length();
            int hintX = text.getLocationAtOffset(end).x + 12;
            if (hintX >= text.getLeftMargin() && hintX + gc.stringExtent(hint).x < text.getClientArea().width)
                gc.drawString(hint, hintX, y, true);
            hits.add(new Hit(new Rectangle(x, y, width, height), change));
        }
    }
}
