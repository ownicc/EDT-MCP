/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

/** Search UI shared by the full-file and detached method viewers. */
final class BslDiffSearchBar
{
    @FunctionalInterface interface Finder
    {
        List<BslDiffSearch.Match> find(String query, BslDiffSearch.Scope scope, boolean matchCase);
    }

    final Composite row;
    private final Text query;
    private final Combo scope;
    private final Button matchCase;
    private final Label count;
    private final Button previous;
    private final Button next;
    private final Finder finder;
    private final Consumer<BslDiffSearch.Match> reveal;
    private final List<StyledText> sources;
    private StyledText lastSource;
    private List<BslDiffSearch.Match> matches = List.of();
    private int current = -1;

    BslDiffSearchBar(Composite parent, Finder finder, Consumer<BslDiffSearch.Match> reveal, List<StyledText> sources)
    {
        this.finder = finder;
        this.reveal = reveal;
        this.sources = sources;
        lastSource = sources.get(0);
        row = new Composite(parent, SWT.NONE);
        row.setLayout(new GridLayout(7, false));
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.exclude = true;
        row.setLayoutData(data);
        row.setVisible(false);
        query = new Text(row, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        query.setMessage(Messages.BslDiff_Find);
        query.setToolTipText(Messages.BslDiff_Find);
        query.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        scope = new Combo(row, SWT.READ_ONLY);
        scope.setItems(Messages.BslDiff_SearchOld, Messages.BslDiff_SearchNew, Messages.BslDiff_SearchBoth);
        scope.select(2);
        scope.setToolTipText(Messages.BslDiff_SearchScope);
        matchCase = new Button(row, SWT.CHECK);
        matchCase.setText(Messages.BslDiff_SearchCase);
        previous = button(Messages.BslDiff_SearchPrevious, () -> navigate(true));
        next = button(Messages.BslDiff_SearchNext, () -> navigate(false));
        count = new Label(row, SWT.NONE);
        button(Messages.BslDiff_SearchClose, this::close);
        query.addModifyListener(event -> refresh());
        scope.addListener(SWT.Selection, event -> refresh());
        matchCase.addListener(SWT.Selection, event -> refresh());
        for (Control control : row.getChildren())
        {
            control.addListener(SWT.KeyDown, event -> {
                if (handleKey(event.keyCode, event.stateMask)) event.doit = false;
            });
            control.addListener(SWT.Traverse, event -> {
                if (event.detail == SWT.TRAVERSE_ESCAPE || event.detail == SWT.TRAVERSE_RETURN) event.doit = false;
            });
        }
        for (StyledText source : sources)
        {
            source.addListener(SWT.FocusIn, event -> lastSource = source);
            source.addVerifyKeyListener(event -> {
                if (handleKey(event.keyCode, event.stateMask)) event.doit = false;
            });
        }
        updateCount();
    }

    private Button button(String label, Runnable action)
    {
        Button button = new Button(row, SWT.PUSH);
        button.setText(label);
        button.addListener(SWT.Selection, event -> action.run());
        return button;
    }

    void open()
    {
        ((GridData)row.getLayoutData()).exclude = false;
        row.setVisible(true);
        row.getParent().layout(true, true);
        query.setFocus();
        query.selectAll();
        if (current < 0 && !matches.isEmpty()) navigate(false);
    }

    void close()
    {
        row.setVisible(false);
        ((GridData)row.getLayoutData()).exclude = true;
        row.getParent().layout(true, true);
        lastSource.setFocus();
    }

    void refresh()
    {
        matches = finder.find(query.getText(), BslDiffSearch.Scope.values()[scope.getSelectionIndex()], matchCase.getSelection());
        current = -1;
        if (row.getVisible()) navigate(false);
        else updateCount();
    }

    private void navigate(boolean backward)
    {
        current = BslDiffSearch.next(current, matches.size(), backward);
        if (current >= 0)
        {
            var match = matches.get(current);
            lastSource = sources.get(match.left() ? 0 : 1);
            reveal.accept(match);
        }
        updateCount();
    }

    private void updateCount()
    {
        String side = current < 0 ? "" : (matches.get(current).left() ? Messages.BslDiff_SearchOld : Messages.BslDiff_SearchNew) + " "; //$NON-NLS-1$ //$NON-NLS-2$
        count.setText(side + (current + 1) + " / " + matches.size()); //$NON-NLS-1$
        previous.setEnabled(!matches.isEmpty());
        next.setEnabled(!matches.isEmpty());
        row.layout(true, true);
    }

    private boolean handleKey(int key, int state)
    {
        if ((state & SWT.MOD1) != 0 && (key == 'f' || key == 'F')) { open(); return true; }
        if (key == SWT.ESC && row.getVisible()) { close(); return true; }
        if (key == SWT.F3 || ((key == SWT.CR || key == SWT.KEYPAD_CR) && query.isFocusControl()))
        {
            boolean opensAtFirst = !row.getVisible() && current < 0 && (state & SWT.SHIFT) == 0;
            if (!row.getVisible()) open();
            if (!opensAtFirst) navigate((state & SWT.SHIFT) != 0);
            return true;
        }
        return false;
    }
}
