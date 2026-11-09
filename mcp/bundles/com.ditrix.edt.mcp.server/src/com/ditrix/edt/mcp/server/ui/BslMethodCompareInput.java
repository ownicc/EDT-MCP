/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.compare.*;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Span;

/** A detached method pair. It has no review service, file handle or acceptance action. */
final class BslMethodCompareInput extends CompareEditorInput
{
    record Fragment(String text, int firstLine, int lastLine) { }
    private final Fragment before;
    private final Fragment after;
    private final DiffNode difference;
    private boolean disposed;

    BslMethodCompareInput(MethodAwareBslDiff.Move move, String current, String proposed)
    {
        super(configuration());
        before = extract(current, move.left());
        after = extract(proposed, move.right());
        String name = move.key().substring(move.key().indexOf(':') + 1);
        setTitle(Messages.BslDiff_MethodTitle + ": " + name + " (" + before.firstLine + " → " + after.firstLine + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        getCompareConfiguration().setLeftLabel(Messages.BslReview_Current + " — " + range(before)); //$NON-NLS-1$
        getCompareConfiguration().setRightLabel(Messages.BslReview_Proposed + " — " + range(after)); //$NON-NLS-1$
        difference = new DiffNode(Differencer.CHANGE, null,
            new Text(Messages.BslReview_Current, before.text), new Text(Messages.BslReview_Proposed, after.text));
    }

    static Fragment extract(String source, List<Span> spans)
    {
        if (spans.isEmpty()) throw new IllegalArgumentException("A method endpoint has no source range"); //$NON-NLS-1$
        Span first = spans.get(0), last = spans.get(spans.size() - 1);
        Document document = new Document(source);
        try
        {
            int start = document.getLineOffset(first.line()) + first.start();
            int end = document.getLineOffset(last.line()) + last.start() + last.length();
            return new Fragment(source.substring(start, end), first.line() + 1, last.line() + 1);
        }
        catch (BadLocationException e) { throw new IllegalArgumentException("Invalid method range", e); } //$NON-NLS-1$
    }

    private static CompareConfiguration configuration()
    {
        CompareConfiguration config = new CompareConfiguration();
        config.setLeftEditable(false);
        config.setRightEditable(false);
        return config;
    }

    private static String range(Fragment fragment) { return fragment.firstLine + "–" + fragment.lastLine; } //$NON-NLS-1$

    private record Text(String name, String source) implements ITypedElement, IEncodedStreamContentAccessor
    {
        @Override public String getName() { return name; }
        @Override public Image getImage() { return null; }
        @Override public String getType() { return "bsl"; } //$NON-NLS-1$
        @Override public String getCharset() { return StandardCharsets.UTF_8.name(); }
        @Override public InputStream getContents() { return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)); }
    }

    @Override protected Object prepareInput(IProgressMonitor monitor) { return difference; }
    @Override public boolean canRunAsJob() { return false; }
    @Override public boolean isDirty() { return false; }
    @Override public boolean isSaveNeeded() { return false; }
    @Override public void setDirty(boolean value) { }
    @Override public void save(IProgressMonitor monitor) { }
    @Override public void saveChanges(IProgressMonitor monitor) { }

    @Override public Control createContents(Composite parent)
    {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        Label title = new Label(root, SWT.WRAP);
        title.setText(getTitle() + "\n" + getCompareConfiguration().getLeftLabel(null) //$NON-NLS-1$
            + "    |    " + getCompareConfiguration().getRightLabel(null)); //$NON-NLS-1$
        title.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Composite area = new Composite(root, SWT.NONE);
        area.setLayout(new GridLayout(1, false));
        area.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        java.util.function.Consumer<String> fallback = reason -> {
            for (Control child : area.getChildren()) child.dispose();
            new Label(area, SWT.WRAP).setText(reason);
            Control standard = BslMethodCompareInput.super.createContents(area);
            standard.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            area.layout(true, true);
        };
        try
        {
            Control viewer = BslStructuralCompare.create(area, before.text, after.text, null, fallback,
                before.firstLine - 1, after.firstLine - 1, false);
            viewer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        }
        catch (Exception e) { fallback.accept(Messages.BslDiff_Unavailable); }
        root.addDisposeListener(event -> {
            Control tail = root;
            while (tail instanceof Composite composite && composite.getChildren().length > 0)
            {
                Control[] children = composite.getChildren();
                tail = children[children.length - 1];
            }
            if (tail == root) handleDispose();
            else tail.addDisposeListener(last -> handleDispose());
        });
        return root;
    }

    @Override protected void handleDispose()
    {
        if (disposed) return;
        disposed = true;
        super.handleDispose();
    }
}
