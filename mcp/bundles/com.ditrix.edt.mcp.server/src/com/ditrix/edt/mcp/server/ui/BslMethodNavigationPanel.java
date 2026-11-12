/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import com.ditrix.edt.mcp.server.ui.BslMethodNavigation.Entry;
import com.ditrix.edt.mcp.server.ui.MethodAwareBslDiff.MethodChange;
import com.ditrix.edt.mcp.server.ui.MethodAwareBslDiff.MethodKind;

/** Native, focus-preserving list over the comparison's immutable identities. */
final class BslMethodNavigationPanel
{
    final Composite root;
    final Text filter;
    final Table table;
    private final Label count;
    private final Label empty;
    private final Map<MethodKind, Button> filters = new EnumMap<>(MethodKind.class);
    private List<Entry> entries = List.of();
    private Entry selected;

    BslMethodNavigationPanel(Composite parent, Consumer<Entry> reveal, Consumer<MethodChange> open)
    {
        root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        count = new Label(root, SWT.NONE);
        count.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filter = new Text(root, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        filter.setMessage(Messages.BslDiff_FilterMethods);
        filter.setToolTipText(Messages.BslDiff_FilterMethods);
        filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Composite types = new Composite(root, SWT.NONE);
        types.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout typeLayout = new GridLayout(2, true);
        typeLayout.marginWidth = 0; typeLayout.marginHeight = 0;
        types.setLayout(typeLayout);
        for (MethodKind kind : List.of(MethodKind.CHANGED, MethodKind.MOVED, MethodKind.ADDED, MethodKind.REMOVED))
        {
            Button button = new Button(types, SWT.CHECK);
            button.setText(kindLabel(kind));
            button.setSelection(true);
            button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            button.addListener(SWT.Selection, event -> refresh());
            filters.put(kind, button);
        }
        table = new Table(root, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        TableColumn name = new TableColumn(table, SWT.LEFT);
        TableColumn changes = new TableColumn(table, SWT.RIGHT);
        changes.setWidth(82);
        table.addListener(SWT.Resize, event -> name.setWidth(Math.max(0, table.getClientArea().width - changes.getWidth())));
        empty = new Label(root, SWT.WRAP);
        empty.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.addListener(SWT.Selection, event -> {
            if (table.getSelectionCount() == 0) return;
            selected = (Entry)table.getSelection()[0].getData();
            reveal.accept(selected);
        });
        table.addListener(SWT.DefaultSelection, event -> {
            if (table.getSelectionCount() == 0) return;
            Entry entry = (Entry)table.getSelection()[0].getData();
            if (entry.method() != null) open.accept(entry.method());
        });
        table.addListener(SWT.MouseMove, event -> {
            TableItem item = table.getItem(new Point(event.x, event.y));
            Entry entry = item == null ? null : (Entry)item.getData();
            String tip = entry == null ? null : description(entry);
            if (!Objects.equals(tip, table.getToolTipText())) table.setToolTipText(tip);
        });
        filter.addModifyListener(event -> refresh());
        filter.addListener(SWT.KeyDown, event -> {
            if (event.keyCode != SWT.ARROW_DOWN && event.keyCode != SWT.CR && event.keyCode != SWT.KEYPAD_CR) return;
            if (table.getItemCount() == 0) return;
            event.doit = false;
            if (table.getSelectionCount() == 0) table.setSelection(0);
            table.setFocus();
            table.notifyListeners(SWT.Selection, new Event());
            if (event.keyCode != SWT.ARROW_DOWN) table.notifyListeners(SWT.DefaultSelection, new Event());
        });
        refresh();
    }

    void show(List<Entry> entries)
    {
        this.entries = entries;
        selected = null;
        refresh();
    }

    void select(MethodChange method, int row)
    {
        selected = entries.stream().filter(entry -> method != null
            ? entry.method() != null && entry.method().key().equals(method.key())
            : entry.method() == null && entry.changedRows().contains(row)).findFirst().orElse(null);
        restoreSelection();
    }

    Entry selectedEntry() { return selected; }

    private void refresh()
    {
        EnumSet<MethodKind> kinds = EnumSet.noneOf(MethodKind.class);
        filters.forEach((kind, button) -> { if (button.getSelection()) kinds.add(kind); });
        table.setRedraw(false);
        try
        {
            table.removeAll();
            for (Entry entry : entries)
            {
                if (!BslMethodNavigation.matches(entry, filter.getText(), kinds)) continue;
                TableItem item = new TableItem(table, SWT.NONE);
                item.setData(entry);
                String prefix = entry.method() == null ? "{ }" : switch (entry.method().kind()) { //$NON-NLS-1$
                    case CHANGED -> "~"; case MOVED -> "↔"; case MOVED_CHANGED -> "↔*"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    case ADDED -> "+"; case REMOVED -> "−"; //$NON-NLS-1$ //$NON-NLS-2$
                };
                item.setText(new String[] {prefix + " " + entry.name(), "+" + entry.added() + " −" + entry.removed()}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            restoreSelection();
        }
        finally { table.setRedraw(true); }
        count.setText(NLS.bind(Messages.BslDiff_MethodCount, table.getItemCount(), entries.size()));
        boolean noRows = table.getItemCount() == 0;
        empty.setText(entries.isEmpty() ? Messages.BslDiff_NoChangedMethods : Messages.BslDiff_NoMatchingMethods);
        ((GridData)empty.getLayoutData()).exclude = !noRows;
        empty.setVisible(noRows);
        ((GridData)table.getLayoutData()).exclude = noRows;
        table.setVisible(!noRows);
        root.layout(true, true);
    }

    private void restoreSelection()
    {
        table.deselectAll();
        for (TableItem item : table.getItems())
            if (item.getData() == selected) { table.setSelection(item); table.showSelection(); break; }
    }

    private static String description(Entry entry)
    {
        if (entry.method() == null) return entry.name() + (entry.fallback() == null ? "" : "\n" + entry.fallback()); //$NON-NLS-1$ //$NON-NLS-2$
        MethodChange method = entry.method();
        return method.name() + " — " + kindLabel(method.kind()) + "\n" + NLS.bind(Messages.BslDiff_MethodRoute, //$NON-NLS-1$ //$NON-NLS-2$
            BslStructuralCompare.methodRange(method.left(), 0), BslStructuralCompare.methodRange(method.right(), 0));
    }

    static String kindLabel(MethodKind kind)
    {
        return switch (kind) {
            case CHANGED -> Messages.BslDiff_MethodChanged;
            case MOVED -> Messages.BslDiff_MethodMoved;
            case MOVED_CHANGED -> Messages.BslDiff_MethodMovedChanged;
            case ADDED -> Messages.BslDiff_MethodAdded;
            case REMOVED -> Messages.BslDiff_MethodRemoved;
        };
    }

    void applyTheme(Color background, Color foreground) { applyTheme(root, background, foreground); }

    private static void applyTheme(Control control, Color background, Color foreground)
    {
        // Leave checkbox indicators to the native theme.
        if (!(control instanceof Button)) control.setBackground(background);
        control.setForeground(foreground);
        if (control instanceof Composite composite)
            for (Control child : composite.getChildren()) applyTheme(child, background, foreground);
    }
}
