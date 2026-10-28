/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ui;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.compare.CompareUI;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews.State;
import com.ditrix.edt.mcp.server.utils.PreparedBslWrite;

/** Bounded nonmodal editor opening, with no write fallback on a UI failure. */
public final class BslWriteReviewUi
{
    private static final Map<String, BslWriteReviewInput> INPUTS = new ConcurrentHashMap<>();
    private BslWriteReviewUi() { }

    /** @return null only after the editor controls exist, otherwise an actionable error JSON. */
    public static String open(PreparedBslWrite write, String reviewId)
    {
        BslWriteReviews reviews = BslWriteReviews.shared();
        if (!PlatformUI.isWorkbenchRunning())
            return refuse(reviews, reviewId, "BSL review requires an EDT workbench. Open EDT or disable 'Review BSL changes before applying' in plugin preferences.");
        CompletableFuture<Boolean> opened = reviews.openingAcknowledgement(reviewId);
        try
        {
            Display display = PlatformUI.getWorkbench().getDisplay();
            if (display.isDisposed()) return refuse(reviews, reviewId, "EDT workbench is shutting down. Retry after it opens.");
            Runnable open = () -> {
                var snapshot = reviews.get(reviewId);
                if (snapshot == null || snapshot.status() != State.AWAITING_APPROVAL)
                {
                    opened.complete(false);
                    return;
                }
                try
                {
                    var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    var page = window == null ? null : window.getActivePage();
                    if (page == null) throw new IllegalStateException("No active EDT workbench page");
                    BslWriteReviewInput input = new BslWriteReviewInput(write, reviewId, reviews);
                    input.created().thenAccept(control -> {
                        INPUTS.put(reviewId, input);
                        control.addDisposeListener(event -> INPUTS.remove(reviewId, input));
                        reviews.acknowledgeOpening(reviewId);
                        var current = reviews.get(reviewId);
                        if (current == null || current.status() == State.CANCELLED || current.status() == State.FAILED)
                            close(input);
                    });
                    CompareUI.openCompareEditorOnPage(input, page);
                }
                catch (Exception e) { opened.completeExceptionally(e); }
            };
            if (display.getThread() == Thread.currentThread())
            {
                open.run();
                if (!opened.isDone()) return refuse(reviews, reviewId, "EDT could not create the review editor immediately. Retry from the MCP client.");
            }
            else display.asyncExec(open);
            return awaitOpening(reviews, reviewId, opened);
        }
        catch (Exception e)
        {
            return refuse(reviews, reviewId, "EDT could not open the review within 5 seconds. No write was applied; retry when the workbench is responsive.");
        }
    }

    public static String awaitOpeningAndFocus(String reviewId)
    {
        BslWriteReviews reviews = BslWriteReviews.shared();
        String error = awaitOpening(reviews, reviewId, reviews.openingAcknowledgement(reviewId));
        if (error == null) focus(reviewId);
        return error;
    }

    private static String awaitOpening(BslWriteReviews reviews, String reviewId, CompletableFuture<Boolean> opened)
    {
        try
        {
            if (opened.get(5, TimeUnit.SECONDS)) return null;
            var snapshot = reviews.get(reviewId);
            return snapshot != null && snapshot.errorJson() != null ? snapshot.errorJson()
                : ToolResult.error("Review was withdrawn before its editor opened. Submit a new write.").toJson();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return refuse(reviews, reviewId, "Interrupted while opening the review. Submit a new write.");
        }
        catch (Exception e)
        {
            return refuse(reviews, reviewId, "EDT could not open the review within 5 seconds. No write was applied; retry when the workbench is responsive.");
        }
    }

    private static String refuse(BslWriteReviews reviews, String id, String reason)
    {
        reviews.failOpening(id, reason);
        return ToolResult.error(reason).put("reviewId", id).toJson();
    }

    public static void focus(String reviewId)
    {
        if (!PlatformUI.isWorkbenchRunning()) return;
        var display = PlatformUI.getWorkbench().getDisplay();
        if (display.isDisposed()) return;
        display.asyncExec(() -> {
            var input = INPUTS.get(reviewId);
            if (input != null && input.getContainer().getWorkbenchPart() instanceof IEditorPart editor)
                editor.getSite().getPage().activate(editor);
        });
    }

    /** Lifecycle cleanup is asynchronous, including when the caller holds the server monitor. */
    public static void closePending()
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            INPUTS.clear();
            return;
        }
        var display = PlatformUI.getWorkbench().getDisplay();
        if (display.isDisposed())
        {
            INPUTS.clear();
            return;
        }
        display.asyncExec(() -> {
            for (BslWriteReviewInput input : INPUTS.values())
            {
                var snapshot = input.snapshot();
                if (snapshot == null || snapshot.status() == State.AWAITING_APPROVAL || snapshot.status() == State.CANCELLED)
                    close(input);
            }
        });
    }

    private static void close(BslWriteReviewInput input)
    {
        if (input.getContainer().getWorkbenchPart() instanceof IEditorPart editor)
            editor.getSite().getPage().closeEditor(editor, false);
    }
}
