/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

import com.ditrix.edt.mcp.server.utils.BslWriteReviews;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews.Snapshot;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews.State;
import com.ditrix.edt.mcp.server.utils.PreparedBslWrite;
import com.google.gson.JsonParser;

/** Detached read-only compare input. The header is the sole acceptance surface. */
public final class BslWriteReviewInput extends CompareEditorInput
{
    private final String reviewId;
    private final BslWriteReviews reviews;
    private final DiffNode difference;
    private final CompletableFuture<Control> created = new CompletableFuture<>();
    private final String identity;
    private AutoCloseable subscription;
    private Button apply;
    private Button reject;
    private Label status;
    private boolean disposed;

    public BslWriteReviewInput(PreparedBslWrite write, String reviewId, BslWriteReviews reviews)
    {
        super(configuration());
        this.reviewId = reviewId;
        this.reviews = reviews;
        identity = write.projectName() + " / " + write.modulePath()
            + (write.baseExists() ? "" : " — " + Messages.BslReview_NewFile);
        setTitle(Messages.BslReview_Title + ": " + write.modulePath());
        difference = new DiffNode(Differencer.CHANGE, null,
            new TextElement(Messages.BslReview_Current, write.baseText()),
            new TextElement(Messages.BslReview_Proposed, write.proposedText()));
    }

    private static CompareConfiguration configuration()
    {
        CompareConfiguration config = new CompareConfiguration();
        config.setLeftEditable(false);
        config.setRightEditable(false);
        config.setLeftLabel(Messages.BslReview_Current);
        config.setRightLabel(Messages.BslReview_Proposed);
        return config;
    }

    private static final class TextElement implements ITypedElement, IEncodedStreamContentAccessor
    {
        private final String name;
        private final byte[] bytes;
        TextElement(String name, String text)
        {
            this.name = name;
            bytes = text.getBytes(StandardCharsets.UTF_8);
        }
        @Override public String getName() { return name; }
        @Override public Image getImage() { return null; }
        @Override public String getType() { return ITypedElement.TEXT_TYPE; }
        @Override public String getCharset() { return StandardCharsets.UTF_8.name(); }
        @Override public InputStream getContents() { return new ByteArrayInputStream(bytes); }
    }

    @Override protected Object prepareInput(IProgressMonitor monitor) { return difference; }
    @Override public boolean canRunAsJob() { return false; }
    @Override public boolean isDirty() { return false; }
    @Override public boolean isSaveNeeded() { return false; }
    @Override public void setDirty(boolean value) { /* Detached comparison is never writable. */ }
    @Override public void save(IProgressMonitor monitor) { /* Acceptance is only a header action. */ }
    @Override public void saveChanges(IProgressMonitor monitor) { /* No live file is attached. */ }

    @Override
    public Control createContents(Composite parent)
    {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        Composite header = new Composite(root, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        header.setLayout(new GridLayout(3, false));
        status = new Label(header, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        apply = new Button(header, SWT.PUSH);
        apply.setText(Messages.BslReview_Apply);
        reject = new Button(header, SWT.PUSH);
        reject.setText(Messages.BslReview_Reject);
        apply.addListener(SWT.Selection, event -> { reviews.apply(reviewId); update(reviews.get(reviewId)); });
        reject.addListener(SWT.Selection, event -> { reviews.reject(reviewId); update(reviews.get(reviewId)); });
        Control comparison = super.createContents(root);
        comparison.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        subscription = reviews.subscribe(reviewId, snapshot -> {
            if (root.isDisposed()) return;
            root.getDisplay().asyncExec(() -> {
                if (!root.isDisposed()) update(snapshot);
            });
        });
        update(reviews.get(reviewId));
        root.addDisposeListener(event -> disposeReview());
        created.complete(root);
        return root;
    }

    private void update(Snapshot snapshot)
    {
        boolean pending = snapshot != null && snapshot.status() == State.AWAITING_APPROVAL;
        apply.setEnabled(pending);
        reject.setEnabled(pending);
        String text = snapshot == null ? Messages.BslReview_Cancelled : statusLabel(snapshot.status());
        if (snapshot != null && snapshot.errorJson() != null)
        {
            try
            {
                var error = JsonParser.parseString(snapshot.errorJson()).getAsJsonObject().get("error");
                if (error != null) text += ": " + error.getAsString();
            }
            catch (RuntimeException ignored) { /* Keep the terminal state visible even for an opaque error. */ }
        }
        status.setText(identity + "\n" + text);
        status.getParent().layout(true, true);
    }

    private static String statusLabel(State state)
    {
        return switch (state)
        {
            case AWAITING_APPROVAL -> Messages.BslReview_Awaiting;
            case APPLYING -> Messages.BslReview_Applying;
            case APPLIED -> Messages.BslReview_Applied;
            case REJECTED -> Messages.BslReview_Rejected;
            case STALE -> Messages.BslReview_Stale;
            case EXPIRED -> Messages.BslReview_Expired;
            case CANCELLED -> Messages.BslReview_Cancelled;
            case FAILED -> Messages.BslReview_Failed;
        };
    }

    CompletableFuture<Control> created() { return created; }
    Snapshot snapshot() { return reviews.get(reviewId); }

    private void disposeReview()
    {
        if (disposed) return;
        disposed = true;
        reviews.reject(reviewId);
        if (subscription != null)
        {
            try { subscription.close(); }
            catch (Exception ignored) { /* Subscription removal is best-effort during UI disposal. */ }
            subscription = null;
        }
    }

    @Override
    protected void handleDispose()
    {
        disposeReview();
        super.handleDispose();
    }
}
