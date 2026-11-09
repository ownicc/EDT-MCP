package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.compare.CompareUI;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;
import org.junit.Assume;
import org.junit.Test;
import com.ditrix.edt.mcp.server.tools.impl.CancelWriteReviewTool;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;
import com.google.gson.JsonParser;

/** A background MCP withdrawal updates the actual comparison controls. Run under Xvfb. */
public class BslWriteReviewCancelWorkbenchTest
{
    @Test public void withdrawalUpdatesTheOpenComparisonWithoutApplying() throws Exception
    {
        Assume.assumeTrue(Boolean.getBoolean("edt.mcp.uiTests"));
        assertTrue("Run with -Pworkbench-tests under Xvfb", PlatformUI.isWorkbenchRunning());
        Display display = PlatformUI.getWorkbench().getDisplay();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var exercised = new java.util.concurrent.atomic.AtomicBoolean();
        display.syncExec(() -> {
            exercised.set(true);
            var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            try
            {
                var reviews = BslWriteReviews.shared();
                var proposal = BslWriteReviewInputTest.proposal();
                String id = reviews.register(proposal, "x", (key, value) -> {
                    fail("Withdrawal must never execute the proposal"); return null;
                }).snapshot().reviewId();
                var input = new BslWriteReviewInput(proposal, id, reviews);
                CompareUI.openCompareEditorOnPage(input, page);
                var root = (Composite)input.created().get(5, TimeUnit.SECONDS).get();
                var header = (Composite)root.getChildren()[0];
                var label = (Label)header.getChildren()[0];
                var apply = (Button)header.getChildren()[1];
                var reject = (Button)header.getChildren()[2];
                assertTrue(apply.isEnabled()); assertTrue(reject.isEnabled());
                var request = new FutureTask<>(() -> new CancelWriteReviewTool().execute(Map.of("reviewId", id)));
                new Thread(request, "cancel-review-mcp").start();
                String json = request.get(5, TimeUnit.SECONDS);
                assertEquals("cancelled", JsonParser.parseString(json).getAsJsonObject().get("status").getAsString());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (apply.isEnabled() && System.nanoTime() < deadline)
                    if (!display.readAndDispatch()) Thread.sleep(5);
                assertFalse(apply.isEnabled()); assertFalse(reject.isEnabled());
                assertTrue(label.getText(), label.getText().contains(Messages.BslReview_Cancelled));
                assertFalse(input.isDirty()); assertFalse(input.isSaveNeeded());
                page.closeAllEditors(false);
                assertEquals(BslWriteReviews.State.CANCELLED, reviews.get(id).status());
                assertNotNull(reviews.register(proposal, "new", (key, value) -> null).snapshot());
            }
            catch (Throwable e) { failure.set(e); }
            finally
            {
                page.closeAllEditors(false);
                BslWriteReviews.shutdownShared();
            }
        });
        assertTrue("Workbench fixture must execute", exercised.get());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
