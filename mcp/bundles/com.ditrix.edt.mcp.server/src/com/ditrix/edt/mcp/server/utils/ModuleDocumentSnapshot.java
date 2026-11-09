/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

/** Read existing buffers only; never connect a buffer, restore an editor, or save. */
public final class ModuleDocumentSnapshot
{
    public record Snapshot(String text, boolean dirty)
    {
        public String documentHash() { return "document:" + ContentHash.of(text); }
    }

    private ModuleDocumentSnapshot() { }

    /** Null means no connected document; unavailable UI and timeout are explicit failures. */
    public static Snapshot read(IFile file) throws Exception
    {
        if (!PlatformUI.isWorkbenchRunning())
            throw new IllegalStateException("Document view requires a running EDT workbench. Use view=disk to read saved source.");
        var display = PlatformUI.getWorkbench().getDisplay();
        FutureTask<Snapshot> task = new FutureTask<>(() -> {
            ITextFileBuffer[] buffers = Arrays.stream(FileBuffers.getTextFileBufferManager().getFileBuffers())
                .filter(ITextFileBuffer.class::isInstance).map(ITextFileBuffer.class::cast).toArray(ITextFileBuffer[]::new);
            return find(file, PlatformUI.getWorkbench().getWorkbenchWindows(), buffers);
        });
        if (display.getThread() == Thread.currentThread()) task.run();
        else display.asyncExec(task);
        try { return task.get(3, TimeUnit.SECONDS); }
        catch (InterruptedException e)
        {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Document read interrupted. Retry when EDT is responsive.", e);
        }
        catch (java.util.concurrent.TimeoutException e)
        {
            task.cancel(false);
            throw new IllegalStateException("EDT UI did not respond within 3 seconds. Retry or use view=disk.", e);
        }
    }

    static Snapshot find(IFile file, IWorkbenchWindow[] windows, ITextFileBuffer[] buffers)
    {
        for (var buffer : buffers)
            if (Objects.equals(file.getFullPath(), buffer.getLocation())
                || (file.getLocation() != null && file.getLocation().equals(buffer.getLocation()))
                || (buffer.getFileStore() != null && sameFile(file.getLocationURI(), buffer.getFileStore().toURI())))
            {
                var document = buffer.getDocument();
                if (document != null) return new Snapshot(document.get(), buffer.isDirty());
            }
        for (var window : windows)
            for (var page : window.getPages())
                for (var reference : page.getEditorReferences())
                {
                    var editor = reference.getEditor(false);
                    if (editor == null || !(editor.getEditorInput() instanceof IFileEditorInput input)
                        || !(file.equals(input.getFile()) || sameFile(file.getLocationURI(), input.getFile().getLocationURI()))) continue;
                    ITextEditor text = editor instanceof ITextEditor t ? t : editor.getAdapter(ITextEditor.class);
                    if (text == null || text.getDocumentProvider() == null) continue;
                    var document = text.getDocumentProvider().getDocument(text.getEditorInput());
                    if (document != null) return new Snapshot(document.get(), editor.isDirty());
                }
        return null;
    }

    private static boolean sameFile(URI left, URI right)
    {
        if (left == null || right == null) return false;
        if (left.equals(right)) return true;
        if (!"file".equalsIgnoreCase(left.getScheme()) || !"file".equalsIgnoreCase(right.getScheme())) return false;
        try { return Files.isSameFile(Path.of(left), Path.of(right)); }
        catch (java.io.IOException | IllegalArgumentException e) { return false; }
    }
}
