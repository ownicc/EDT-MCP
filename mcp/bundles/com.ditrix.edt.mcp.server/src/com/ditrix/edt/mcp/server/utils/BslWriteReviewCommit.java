/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.UIJob;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews.Outcome;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews.State;

/** Final guards and the short, serialized UI write. Model export runs on the caller's job. */
public final class BslWriteReviewCommit
{
    private static final String DIRTY = "The module has unsaved edits. Save or discard them in EDT, then submit a new write.";
    private static final String NO_UI = "BSL review requires an available EDT workbench. Open EDT or disable 'Review BSL changes before applying' in plugin preferences.";

    private BslWriteReviewCommit() { }

    /** Timeout and UI entry compete before the first filesystem mutation. */
    static final class EntryGate
    {
        private boolean cancelled;
        private boolean entered;
        synchronized boolean enter(BooleanSupplier registryGate)
        {
            if (cancelled || entered || !registryGate.getAsBoolean()) return false;
            entered = true;
            return true;
        }
        synchronized boolean cancelBeforeEntry()
        {
            if (entered) return false;
            cancelled = true;
            return true;
        }
    }

    public static Outcome commit(PreparedBslWrite write, BooleanSupplier enterMutation,
        Function<PreparedBslWrite, String> afterWrite)
    {
        if (!PlatformUI.isWorkbenchRunning()) return failure(NO_UI);
        EntryGate gate = new EntryGate();
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        UIJob job;
        try
        {
            ProjectContext ctx = ProjectContext.of(write.projectName());
            if (!ctx.exists() || !ctx.project().isOpen()) return stale("Project is closed or missing.");
            IFile target = BslModuleUtils.resolveModuleFile(ctx.project(), write.modulePath());
            if (target == null || !target.getProject().equals(ctx.project())) return stale("Module target changed.");
            job = new UIJob(PlatformUI.getWorkbench().getDisplay(), "Commit reviewed BSL changes")
            {
                @Override
                public IStatus runInUIThread(IProgressMonitor monitor)
                {
                    try
                    {
                        Outcome refusal = validateTarget(write, target);
                        result.complete(refusal != null ? refusal : writeIfUnchanged(write, target,
                            isDirty(target), () -> gate.enter(enterMutation)));
                    }
                    catch (Exception e)
                    {
                        result.complete(failure("Cannot validate the reviewed module: " + e.getMessage()));
                    }
                    return Status.OK_STATUS;
                }
            };
            // Native UIJob acquires the scheduling rule off SWT and sets its running thread
            // before the UI callback. No syncExec is used while holding a workspace rule.
            // A project rule also covers creation of any currently missing parent folders.
            job.setRule(ctx.project());
            job.setSystem(true);
            job.schedule();
        }
        catch (Exception e)
        {
            return failure("Cannot schedule the reviewed write: " + e.getMessage());
        }
        Outcome written;
        try
        {
            written = result.get(5, TimeUnit.SECONDS);
        }
        catch (TimeoutException e)
        {
            if (gate.cancelBeforeEntry())
            {
                job.cancel();
                return failure("EDT could not enter the reviewed write within 5 seconds. No mutation was started; submit a new review when EDT is responsive.");
            }
            // The mutation has entered an opaque platform API. Report its real outcome;
            // a timeout must never turn a potentially committed append into a retryable refusal.
            written = awaitEnteredWrite(result);
        }
        catch (InterruptedException e)
        {
            if (gate.cancelBeforeEntry())
            {
                job.cancel();
                Thread.currentThread().interrupt();
                return new Outcome(State.CANCELLED, false, null, null);
            }
            written = awaitEnteredWrite(result);
            Thread.currentThread().interrupt();
        }
        catch (Exception e)
        {
            return failure("Cannot obtain the write outcome: " + e.getMessage());
        }
        if (written.status() != State.APPLIED) return written;
        return finishFollowUp(write, afterWrite);
    }

    private static Outcome awaitEnteredWrite(CompletableFuture<Outcome> result)
    {
        boolean interrupted = false;
        try
        {
            for (;;)
            {
                try { return result.get(); }
                catch (InterruptedException e) { interrupted = true; }
                catch (Exception e)
                {
                    return new Outcome(State.FAILED, null, null,
                        ToolResult.errorWithUnknownMutationOutcome("Cannot observe the entered write: " + e.getMessage()).toJson());
                }
            }
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    static Outcome finishFollowUp(PreparedBslWrite write, Function<PreparedBslWrite, String> afterWrite)
    {
        try
        {
            String result = afterWrite.apply(write);
            return ToolResult.isErrorPayload(result)
                ? new Outcome(State.FAILED, true, null, ToolResult.markErrorAfterMutation(result))
                : new Outcome(State.APPLIED, true, result, null);
        }
        catch (Exception e)
        {
            return new Outcome(State.FAILED, true, null,
                ToolResult.errorAfterMutation("Source was written, but follow-up failed: " + e.getMessage()).toJson());
        }
    }

    private static Outcome validateTarget(PreparedBslWrite write, IFile target) throws Exception
    {
        ProjectContext ctx = ProjectContext.of(write.projectName());
        if (!ctx.exists() || !ctx.project().isOpen()
            || !ctx.project().equals(target.getProject())
            || !Objects.equals(write.projectLocation(), ctx.project().getLocationURI().toString())
            || !write.fileLocation().equals(canonicalLocation(target)))
            return stale("Project or module identity changed. Read the module and submit a new review.");
        var root = ctx.resolveMetadataRoot();
        if (!root.ok()) return new Outcome(State.FAILED, false, null, root.errorJson());
        String locked = VendorSupportGuard.refusalForModuleFile(ctx.project(), root.scope(), target);
        return locked == null ? null : failure(locked);
    }

    static Outcome writeIfUnchanged(PreparedBslWrite write, IFile target, boolean dirty, BooleanSupplier enterMutation)
    {
        boolean entered = false;
        try
        {
            if (dirty) return failure(DIRTY);
            boolean physicalExists = target.getLocation() != null && Files.exists(target.getLocation().toFile().toPath());
            if (target.exists() != write.baseExists() || physicalExists != write.baseExists())
                return stale("Module existence changed. Read the module and submit a new review.");
            if (write.baseExists() && !PreparedBslWrite.sha256(readBytes(target)).equals(write.baseHash()))
                return stale("Module bytes changed since preview. Read the module and submit a new review.");
            if (!enterMutation.getAsBoolean()) return new Outcome(State.CANCELLED, false, null, null);
            entered = true;
            try (InputStream source = new ByteArrayInputStream(write.outputBytes()))
            {
                if (write.baseExists()) target.setContents(source, IResource.FORCE | IResource.KEEP_HISTORY, null);
                else
                {
                    createFolders(target.getParent());
                    target.create(source, false, null);
                }
            }
            return new Outcome(State.APPLIED, true, null, null);
        }
        catch (Exception e)
        {
            return new Outcome(State.FAILED, entered ? null : false, null,
                (entered ? ToolResult.errorWithUnknownMutationOutcome("Write outcome is unknown: " + e.getMessage())
                    : ToolResult.error("Cannot validate the module: " + e.getMessage())).toJson());
        }
    }

    private static void createFolders(IContainer container) throws Exception
    {
        if (container instanceof IFolder folder && !folder.exists())
        {
            createFolders(folder.getParent());
            folder.create(false, true, null);
        }
    }

    public static byte[] readBytes(IFile file) throws Exception
    {
        try (InputStream stream = file.getContents(true)) { return stream.readAllBytes(); }
    }

    /** Resolve symbolic links before capturing identity; external linked modules are refused. */
    public static String canonicalLocation(IFile file) throws Exception
    {
        if (file.getLocation() == null || file.getProject().getLocation() == null)
            throw new IllegalStateException("Review requires a local module inside its project.");
        var target = file.getLocation().toFile().getCanonicalFile().toPath();
        var project = file.getProject().getLocation().toFile().getCanonicalFile().toPath();
        if (!target.startsWith(project) || target.equals(project))
            throw new IllegalStateException("The module resolves outside its project. Choose a local src/-relative module.");
        return target.toUri().toString();
    }

    public static String preparationRefusal(IFile file)
    {
        if (!PlatformUI.isWorkbenchRunning()) return NO_UI;
        CompletableFuture<Boolean> dirty = new CompletableFuture<>();
        try
        {
            var display = PlatformUI.getWorkbench().getDisplay();
            if (display.isDisposed()) return NO_UI;
            if (display.getThread() == Thread.currentThread()) return isDirty(file) ? DIRTY : null;
            display.asyncExec(() -> {
                try { dirty.complete(isDirty(file)); }
                catch (Exception e) { dirty.completeExceptionally(e); }
            });
            return dirty.get(5, TimeUnit.SECONDS) ? DIRTY : null;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return "Interrupted while checking unsaved edits. Submit the write again when EDT is responsive.";
        }
        catch (Exception e)
        {
            return "Cannot check unsaved edits in EDT. No write was prepared; retry when EDT is responsive.";
        }
    }

    private static boolean isDirty(IFile file)
    {
        ITextFileBuffer[] buffers = Arrays.stream(FileBuffers.getTextFileBufferManager().getFileBuffers())
            .filter(ITextFileBuffer.class::isInstance).map(ITextFileBuffer.class::cast).toArray(ITextFileBuffer[]::new);
        return isDirty(file, PlatformUI.getWorkbench().getWorkbenchWindows(), buffers);
    }

    static boolean isDirty(IFile file, IWorkbenchWindow[] windows, ITextFileBuffer[] buffers)
    {
        for (ITextFileBuffer buffer : buffers)
        {
            if (buffer.isDirty() && (Objects.equals(file.getFullPath(), buffer.getLocation())
                || (file.getLocation() != null && file.getLocation().equals(buffer.getLocation()))
                || (buffer.getFileStore() != null && samePhysicalFile(file.getLocationURI(), buffer.getFileStore().toURI()))))
                return true;
        }
        for (IWorkbenchWindow window : windows)
            for (var page : window.getPages())
                for (var reference : page.getEditorReferences())
                {
                    var editor = reference.getEditor(false);
                    if (editor != null && editor.isDirty() && editor.getEditorInput() instanceof IFileEditorInput input
                        && (file.equals(input.getFile()) || samePhysicalFile(file.getLocationURI(), input.getFile().getLocationURI())))
                        return true;
                }
        return false;
    }

    private static boolean samePhysicalFile(URI left, URI right)
    {
        if (left == null || right == null) return false;
        if (left.equals(right)) return true;
        if (!"file".equalsIgnoreCase(left.getScheme()) || !"file".equalsIgnoreCase(right.getScheme())) return false;
        try { return Files.isSameFile(Path.of(left), Path.of(right)); }
        catch (java.io.IOException | IllegalArgumentException e) { return false; }
    }

    private static Outcome failure(String message)
    {
        return new Outcome(State.FAILED, false, null, ToolResult.error(message).toJson());
    }

    private static Outcome stale(String message)
    {
        return new Outcome(State.STALE, false, null, ToolResult.error(message).toJson());
    }
}
