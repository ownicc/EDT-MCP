package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Test;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;

public class BslWriteReviewUiTest
{
    @After public void cleanup() { BslWriteReviews.shutdownShared(); }

    @Test
    public void noWorkbenchRefusesTheReviewAndNeverCallsItsCommit()
    {
        var reviews = BslWriteReviews.shared();
        var write = BslWriteReviewInputTest.proposal();
        String id = reviews.register(write, "x", (key, value) -> {
            fail("UI failure must not apply"); return null;
        }).snapshot().reviewId();
        String error = BslWriteReviewUi.open(write, id);
        assertNotNull(error);
        assertTrue(error, error.contains("workbench"));
        assertEquals(BslWriteReviews.State.FAILED, reviews.get(id).status());
    }
}
