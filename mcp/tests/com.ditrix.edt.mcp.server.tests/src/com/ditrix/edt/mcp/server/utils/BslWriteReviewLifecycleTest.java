package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.Test;

public class BslWriteReviewLifecycleTest
{
    @Test
    public void closeAndReopenAtTheSameLocationInvalidatesTheOriginalProposal() throws Exception
    {
        var reviews = BslWriteReviews.shared();
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("ReviewLifecycleTest");
        try
        {
            project.create(null); project.open(null);
            long token = reviews.projectToken(project.getName());
            var location = project.getLocationURI();
            var write = new PreparedBslWrite(project.getName(), location.toString(), token,
                "a.bsl", location.resolve("src/a.bsl").toString(), false, "", "", "new",
                new byte[] { 1 }, 0, 1, "replace", false, null);
            String id = reviews.register(write, "x", (key, value) -> { fail("Old project must not apply"); return null; })
                .snapshot().reviewId();
            project.close(null); project.open(null);
            assertEquals(location, project.getLocationURI());
            assertNotEquals(token, reviews.projectToken(project.getName()));
            reviews.apply(id);
            assertEquals(BslWriteReviews.State.CANCELLED, reviews.get(id).status());
        }
        finally
        {
            if (project.exists()) project.delete(true, true, null);
            BslWriteReviews.shutdownShared();
        }
    }

    @Test
    public void pluginUnloadDropsIdsAndRevokesActionsOnTheOldRegistry()
    {
        var reviews = BslWriteReviews.shared();
        var write = BslWriteReviewsTest.write("a.bsl", "new");
        String id = reviews.register(write, "x", (key, value) -> { fail("Unloaded review must not apply"); return null; })
            .snapshot().reviewId();
        BslWriteReviews.shutdownShared();
        try
        {
            reviews.apply(id);
            assertEquals(BslWriteReviews.State.CANCELLED, reviews.get(id).status());
            assertNull(BslWriteReviews.shared().get(id));
        }
        finally { BslWriteReviews.shutdownShared(); }
    }
}
