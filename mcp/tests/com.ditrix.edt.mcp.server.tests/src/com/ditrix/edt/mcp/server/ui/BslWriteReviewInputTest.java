package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.junit.After;
import org.junit.Test;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;
import com.ditrix.edt.mcp.server.utils.PreparedBslWrite;

public class BslWriteReviewInputTest
{
    static PreparedBslWrite proposal()
    {
        return new PreparedBslWrite("Project", "file:/p", 0, "a.bsl", "file:/p/src/a.bsl", true,
            "hash", "old\r\n", "new\r\n", "new\r\n".getBytes(StandardCharsets.UTF_8),
            1, 1, "replace", false, null);
    }

    @After public void cleanup() { BslWriteReviews.shutdownShared(); }

    @Test
    public void comparisonNeverEnablesEitherEditableSide() throws Exception
    {
        var reviews = BslWriteReviews.shared();
        var input = new BslWriteReviewInput(proposal(), "id", reviews);
        assertFalse(input.getCompareConfiguration().isLeftEditable());
        assertFalse(input.getCompareConfiguration().isRightEditable());
        var node = (DiffNode)input.prepareInput(null);
        assertFalse(node.getLeft() instanceof IEditableContent);
        assertFalse(node.getRight() instanceof IEditableContent);
        try (var in = ((IStreamContentAccessor)node.getLeft()).getContents())
        { assertEquals("old\r\n", new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
        try (var in = ((IStreamContentAccessor)node.getRight()).getContents())
        { assertEquals("new\r\n", new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
    }

    @Test
    public void saveDoesNotApplyAndClosingAnAwaitingInputRejects() throws Exception
    {
        var reviews = BslWriteReviews.shared();
        String id = reviews.register(proposal(), "x", (key, value) -> {
            fail("Save or close must not commit"); return null;
        }).snapshot().reviewId();
        var input = new BslWriteReviewInput(proposal(), id, reviews);
        input.saveChanges(null);
        input.save(null);
        assertFalse(input.isDirty());
        assertFalse(input.isSaveNeeded());
        assertEquals(BslWriteReviews.State.AWAITING_APPROVAL, reviews.get(id).status());
        input.handleDispose();
        assertEquals(BslWriteReviews.State.REJECTED, reviews.get(id).status());
    }
}
