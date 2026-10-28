package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.runtime.Path;
import org.eclipse.ui.IWorkbenchWindow;
import org.junit.Test;

public class BslWriteReviewCommitTest
{
    private PreparedBslWrite proposal(java.nio.file.Path path, boolean exists, String mode)
    {
        return new PreparedBslWrite("p", path.getParent().toUri().toString(), 0, "a.bsl",
            path.toUri().toString(), exists, PreparedBslWrite.sha256("old\r\n".getBytes(StandardCharsets.UTF_8)),
            "old\r\n", "accepted\r\n", "accepted\r\n".getBytes(StandardCharsets.UTF_8), 1, 1, mode, false, null);
    }

    private IFile file(java.nio.file.Path path, boolean exists) throws Exception
    {
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(exists);
        when(file.getLocation()).thenReturn(Path.fromOSString(path.toString()));
        when(file.getLocationURI()).thenReturn(path.toUri());
        when(file.getFullPath()).thenReturn(new Path("/p/src/a.bsl"));
        when(file.getContents(true)).thenAnswer(invocation -> Files.newInputStream(path));
        doAnswer(invocation -> { try (InputStream in = invocation.getArgument(0)) {
            Files.write(path, in.readAllBytes()); } return null;
        }).when(file).setContents(any(InputStream.class), anyInt(), isNull());
        return file;
    }

    @Test
    public void changedBaseIsStaleForEveryWriteMode() throws Exception
    {
        var directory = Files.createTempDirectory("bsl-review");
        var path = directory.resolve("a.bsl");
        try
        {
            Files.writeString(path, "human edit\r\n");
            for (String mode : new String[] { "replace", "append", "searchReplace", "replaceMethod", "insertBefore", "insertAfter" })
            {
                var outcome = BslWriteReviewCommit.writeIfUnchanged(proposal(path, true, mode), file(path, true), false,
                    () -> { fail("stale proposal must not enter mutation"); return false; });
                assertEquals(mode, BslWriteReviews.State.STALE, outcome.status());
                assertEquals(Boolean.FALSE, outcome.mutationCommitted());
                assertEquals("human edit\r\n", Files.readString(path));
            }
        }
        finally { Files.deleteIfExists(path); Files.delete(directory); }
    }

    @Test
    public void writesOnlyTheAcceptedBytesAndHonorsDirtyAndCancelledGuards() throws Exception
    {
        var directory = Files.createTempDirectory("bsl-review");
        var path = directory.resolve("a.bsl");
        try
        {
            Files.writeString(path, "old\r\n");
            var proposal = proposal(path, true, "append");
            assertEquals(BslWriteReviews.State.FAILED,
                BslWriteReviewCommit.writeIfUnchanged(proposal, file(path, true), true, () -> true).status());
            assertEquals("old\r\n", Files.readString(path));
            assertEquals(BslWriteReviews.State.CANCELLED,
                BslWriteReviewCommit.writeIfUnchanged(proposal, file(path, true), false, () -> false).status());
            assertEquals("old\r\n", Files.readString(path));
            assertEquals(Boolean.TRUE,
                BslWriteReviewCommit.writeIfUnchanged(proposal, file(path, true), false, () -> true).mutationCommitted());
            assertArrayEquals("accepted\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path));
        }
        finally { Files.deleteIfExists(path); Files.delete(directory); }
    }

    @Test
    public void unexpectedFileCreationCannotBeOverwritten() throws Exception
    {
        var directory = Files.createTempDirectory("bsl-review");
        var path = directory.resolve("a.bsl");
        try
        {
            Files.writeString(path, "someone created this");
            assertEquals(BslWriteReviews.State.STALE,
                BslWriteReviewCommit.writeIfUnchanged(proposal(path, false, "replace"), file(path, false), false, () -> true).status());
            assertEquals("someone created this", Files.readString(path));
        }
        finally { Files.deleteIfExists(path); Files.delete(directory); }
    }

    @Test
    public void opaqueWriteFailureKeepsUnknownMutationOutcome() throws Exception
    {
        var directory = Files.createTempDirectory("bsl-review");
        var path = directory.resolve("a.bsl");
        try
        {
            Files.writeString(path, "old\r\n");
            IFile file = file(path, true);
            doThrow(new IllegalStateException("opaque failure")).when(file)
                .setContents(any(InputStream.class), anyInt(), isNull());
            var outcome = BslWriteReviewCommit.writeIfUnchanged(proposal(path, true, "replace"), file, false, () -> true);
            assertEquals(BslWriteReviews.State.FAILED, outcome.status());
            assertNull(outcome.mutationCommitted());
            assertTrue(outcome.errorJson().contains("mutationOutcomeUnknown"));
        }
        finally { Files.deleteIfExists(path); Files.delete(directory); }
    }

    @Test
    public void dirtyTextBufferIsDetectedWithoutAnEditor() throws Exception
    {
        IFile file = mock(IFile.class);
        when(file.getFullPath()).thenReturn(new Path("/p/src/a.bsl"));
        ITextFileBuffer buffer = mock(ITextFileBuffer.class);
        when(buffer.getLocation()).thenReturn(new Path("/p/src/a.bsl"));
        when(buffer.isDirty()).thenReturn(true);
        assertTrue(BslWriteReviewCommit.isDirty(file, new IWorkbenchWindow[0], new ITextFileBuffer[] { buffer }));
    }

    @Test
    public void timeoutRevokesAnApplicationBeforeMutationEntry()
    {
        var gate = new BslWriteReviewCommit.EntryGate();
        assertTrue(gate.cancelBeforeEntry());
        assertFalse(gate.enter(() -> { fail("late UI callback"); return true; }));
        var entered = new BslWriteReviewCommit.EntryGate();
        assertTrue(entered.enter(() -> true));
        assertFalse(entered.cancelBeforeEntry());
    }
    @Test
    public void followUpFailureReportsThatSourceHasAlreadyBeenWritten()
    {
        var write = proposal(java.nio.file.Path.of("/tmp/project/a.bsl"), true, "append");
        var outcome = BslWriteReviewCommit.finishFollowUp(write, value -> {
            throw new IllegalStateException("export failed");
        });
        assertEquals(BslWriteReviews.State.FAILED, outcome.status());
        assertEquals(Boolean.TRUE, outcome.mutationCommitted());
        assertTrue(outcome.errorJson().contains("mutationCommitted"));
    }

    @Test
    public void dirtyEditorInASecondWindowIsDetected()
    {
        IFile file = mock(IFile.class);
        var first = mock(IWorkbenchWindow.class);
        var second = mock(IWorkbenchWindow.class);
        var page = mock(org.eclipse.ui.IWorkbenchPage.class);
        var reference = mock(org.eclipse.ui.IEditorReference.class);
        var editor = mock(org.eclipse.ui.IEditorPart.class);
        var input = mock(org.eclipse.ui.IFileEditorInput.class);
        when(first.getPages()).thenReturn(new org.eclipse.ui.IWorkbenchPage[0]);
        when(second.getPages()).thenReturn(new org.eclipse.ui.IWorkbenchPage[] { page });
        when(page.getEditorReferences()).thenReturn(new org.eclipse.ui.IEditorReference[] { reference });
        when(reference.getEditor(false)).thenReturn(editor);
        when(editor.isDirty()).thenReturn(true);
        when(editor.getEditorInput()).thenReturn(input);
        when(input.getFile()).thenReturn(file);
        assertTrue(BslWriteReviewCommit.isDirty(file,
            new IWorkbenchWindow[] { first, second }, new ITextFileBuffer[0]));
    }

}
