package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.compare.CompareUI;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;
import org.junit.Assume;
import org.junit.Test;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;
import com.ditrix.edt.mcp.server.utils.PreparedBslWrite;

/** Exercise the actual review input with the preference, without a JVM opt-in. */
public class BslDiffPreferenceWorkbenchTest
{
    @Test public void preferenceSelectsTheViewerForEachNewReview() throws Exception
    {
        Assume.assumeTrue(Boolean.getBoolean("edt.mcp.uiTests"));
        assertTrue(PlatformUI.isWorkbenchRunning());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            String key = PreferenceConstants.PREF_BSL_METHOD_DIFF_ENABLED;
            boolean defaultValue = store.isDefault(key), previous = store.getBoolean(key);
            String engine = System.getProperty(MethodAwareBslDiff.ENGINE_PROPERTY);
            String executable = System.getProperty(StructuralBslDiff.EXECUTABLE_PROPERTY);
            try
            {
                System.clearProperty(MethodAwareBslDiff.ENGINE_PROPERTY);
                System.clearProperty(StructuralBslDiff.EXECUTABLE_PROPERTY);
                for (boolean enabled : new boolean[] {false, true, false})
                {
                    store.setValue(key, enabled);
                    var reviews = BslWriteReviews.shared();
                    var proposal = BslWriteReviewInputTest.proposal();
                    String id = reviews.register(proposal, "preference", (target, value) -> {
                        fail("Viewer selection must never apply a proposal"); return null;
                    }).snapshot().reviewId();
                    var input = new BslWriteReviewInput(proposal, id, reviews);
                    var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    CompareUI.openCompareEditorOnPage(input, page);
                    Composite root = (Composite)input.created().get(5, TimeUnit.SECONDS).get();
                    assertNotNull(root);
                    assertEquals("The actual viewer must follow the saved preference", enabled,
                        hasMethodComparison(root));
                    assertEquals(BslWriteReviews.State.AWAITING_APPROVAL, reviews.get(id).status());
                    assertFalse(input.isSaveNeeded());
                    page.closeAllEditors(false);
                    assertEquals(BslWriteReviews.State.REJECTED, reviews.get(id).status());
                }
            }
            catch (Throwable e) { failure.set(e); }
            finally
            {
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors(false);
                if (defaultValue) store.setToDefault(key); else store.setValue(key, previous);
                restore(MethodAwareBslDiff.ENGINE_PROPERTY, engine);
                restore(StructuralBslDiff.EXECUTABLE_PROPERTY, executable);
                BslWriteReviews.shutdownShared();
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test public void fallbackPreservesReasonAndReportsElapsedTime() throws Exception
    {
        Assume.assumeTrue(Boolean.getBoolean("edt.mcp.uiTests"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            String key = PreferenceConstants.PREF_BSL_METHOD_DIFF_ENABLED;
            boolean wasDefault = store.isDefault(key), previous = store.getBoolean(key);
            String engine = System.getProperty(MethodAwareBslDiff.ENGINE_PROPERTY);
            String executable = System.getProperty(StructuralBslDiff.EXECUTABLE_PROPERTY);
            var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            try
            {
                System.clearProperty(MethodAwareBslDiff.ENGINE_PROPERTY);
                for (boolean oversized : new boolean[] {true, false})
                {
                    store.setValue(key, oversized);
                    if (oversized) System.clearProperty(StructuralBslDiff.EXECUTABLE_PROPERTY);
                    else System.setProperty(StructuralBslDiff.EXECUTABLE_PROPERTY,
                        new java.io.File("missing-bsl-diff-executable").getAbsolutePath());
                    String before = oversized ? "x".repeat(524289) : "Return 1;\n";
                    String after = before + "// edit\n";
                    var proposal = new PreparedBslWrite("Project", "file:/p", 0, "a.bsl", "file:/p/src/a.bsl",
                        true, "hash", before, after, after.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        1, 1, "replace", false, null);
                    var reviews = BslWriteReviews.shared();
                    String id = reviews.register(proposal, "fallback", (target, value) -> {
                        fail("Fallback must never apply a proposal"); return null;
                    }).snapshot().reviewId();
                    var input = new BslWriteReviewInput(proposal, id, reviews);
                    CompareUI.openCompareEditorOnPage(input, page);
                    Composite root = (Composite)input.created().get(5, TimeUnit.SECONDS).get();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    String notice;
                    while ((notice = fallbackNotice(root)) == null && System.nanoTime() < deadline)
                    {
                        if (!root.getDisplay().readAndDispatch()) Thread.sleep(1);
                    }
                    assertNotNull("A failed comparison must explain the standard viewer", notice);
                    assertTrue("Fallback must report elapsed milliseconds: " + notice,
                        notice.matches("(?s).*\\b[0-9]+ (?:ms|мс)\\b.*"));
                    assertTrue(notice, notice.contains(oversized ? "524288"
                        : "The BSL-enabled difftastic executable is unavailable"));
                    assertFalse(hasMethodComparison(root));
                    assertEquals(BslWriteReviews.State.AWAITING_APPROVAL, reviews.get(id).status());
                    page.closeAllEditors(false);
                    assertEquals(BslWriteReviews.State.REJECTED, reviews.get(id).status());
                }
            }
            catch (Throwable e) { failure.set(e); }
            finally
            {
                page.closeAllEditors(false);
                if (wasDefault) store.setToDefault(key); else store.setValue(key, previous);
                restore(MethodAwareBslDiff.ENGINE_PROPERTY, engine);
                restore(StructuralBslDiff.EXECUTABLE_PROPERTY, executable);
                BslWriteReviews.shutdownShared();
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static String fallbackNotice(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Label label && label.getText().startsWith(Messages.BslDiff_Unavailable))
                return label.getText();
            if (child instanceof Composite nested)
            {
                String notice = fallbackNotice(nested);
                if (notice != null) return notice;
            }
        }
        return null;
    }

    private static boolean hasMethodComparison(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Button button && Messages.BslDiff_CompareMethod.equals(button.getText()))
                return true;
            if (child instanceof Composite nested && hasMethodComparison(nested)) return true;
        }
        return false;
    }

    private static void restore(String property, String previous)
    {
        if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
    }
}
