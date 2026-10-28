/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonParser;

/** Bounded in-memory decisions; only accepted work occupies an executable Eclipse job. */
public final class BslWriteReviews implements AutoCloseable
{
    public enum State
    {
        AWAITING_APPROVAL("awaitingApproval"), APPLYING("applying"), APPLIED("applied"),
        REJECTED("rejected"), STALE("stale"), EXPIRED("expired"), CANCELLED("cancelled"), FAILED("failed");

        private final String wireName;
        State(String wireName) { this.wireName = wireName; }
        public String wireName() { return wireName; }
        public boolean terminal() { return this != AWAITING_APPROVAL && this != APPLYING; }
    }

    public record Outcome(State status, Boolean mutationCommitted, String resultMarkdown, String errorJson) { }
    public record Admission(Snapshot snapshot, boolean reused, String errorJson) { }
    public record Snapshot(String reviewId, State status, String projectName, String modulePath,
        Instant createdAt, Instant expiresAt, Instant completedAt, Boolean mutationCommitted,
        String resultMarkdown, String errorJson, String reason)
    {
        public String toJson()
        {
            ToolResult result = ToolResult.success().put("reviewId", reviewId)
                .put("status", status.wireName()).put("projectName", projectName)
                .put("modulePath", modulePath).put("createdAt", createdAt.toString())
                .put("expiresAt", expiresAt.toString())
                .put("nextTool", "get_write_review_status").put("pollAfterSeconds", 3);
            if (mutationCommitted != null)
            {
                result.put("mutationCommitted", mutationCommitted.booleanValue())
                    .put("applied", mutationCommitted.booleanValue());
            }
            else
            {
                result.put("mutationOutcomeUnknown", true);
            }
            if (completedAt != null) result.put("completedAt", completedAt.toString());
            if (reason != null) result.put("reason", reason);
            if (resultMarkdown != null) result.put("resultMarkdown", resultMarkdown);
            if (errorJson != null) result.put("errorDetails", JsonParser.parseString(errorJson));
            return result.toJson();
        }
    }

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final long MAX_PAYLOAD = 16L * 1024 * 1024;
    private static BslWriteReviews shared;

    private final Clock clock;
    private final Executor executor;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Long> projectTokens = new HashMap<>();
    private long retainedPayload;
    private boolean accepting = true;
    private boolean closed;
    private IWorkspace workspace;
    private IResourceChangeListener resourceListener;
    private Job expiryJob;

    private static final class Entry
    {
        Snapshot snapshot;
        PreparedBslWrite write;
        BiFunction<String, PreparedBslWrite, Outcome> commit;
        final String digest;
        final String target;
        final long payload;
        final List<Consumer<Snapshot>> listeners = new ArrayList<>();
        boolean mutationEntered;

        Entry(PreparedBslWrite write, String digest,
            BiFunction<String, PreparedBslWrite, Outcome> commit, Instant now)
        {
            this.write = write;
            this.digest = digest;
            this.commit = commit;
            target = write.fileLocation();
            payload = write.payloadBytes();
            snapshot = new Snapshot(UUID.randomUUID().toString(), State.AWAITING_APPROVAL,
                write.projectName(), write.modulePath(), now, now.plus(TTL), null, false, null, null, null);
        }
    }

    BslWriteReviews(Clock clock)
    {
        this(clock, work -> {
            Job job = Job.create("Apply reviewed BSL changes", monitor -> {
                work.run();
                return Status.OK_STATUS;
            });
            job.setSystem(true);
            job.schedule();
        });
    }

    BslWriteReviews(Clock clock, Executor executor)
    {
        this.clock = clock;
        this.executor = executor;
    }

    public static synchronized BslWriteReviews shared()
    {
        if (shared == null)
        {
            shared = new BslWriteReviews(Clock.systemUTC());
            shared.attachLifecycle();
        }
        return shared;
    }

    public static synchronized void shutdownShared()
    {
        if (shared != null) shared.close();
        shared = null;
    }

    public synchronized long projectToken(String projectName)
    {
        return projectTokens.getOrDefault(projectName, 0L);
    }

    public synchronized void invalidateProject(String projectName)
    {
        projectTokens.put(projectName, projectToken(projectName) + 1);
        for (Entry entry : List.copyOf(entries.values()))
            if (entry.snapshot.projectName().equals(projectName)) cancel(entry, "project-changed");
    }

    public synchronized Admission register(PreparedBslWrite write, String digest,
        BiFunction<String, PreparedBslWrite, Outcome> commit)
    {
        expireDue();
        if (closed || !accepting) return refused("Review service is stopped. Start the MCP server and retry.");
        if (projectToken(write.projectName()) != write.projectToken())
            return refused("Project changed during preparation. Read the module again and retry.");
        int active = 0;
        for (Entry entry : entries.values())
        {
            if (entry.snapshot.status().terminal()) continue;
            active++;
            if (entry.target.equals(write.fileLocation()))
            {
                if (entry.snapshot.status() == State.AWAITING_APPROVAL && entry.digest.equals(digest))
                    return new Admission(entry.snapshot, true, null);
                return new Admission(null, false, ToolResult.error(
                    "A review is already pending for this module. Resolve it in EDT or poll its status.")
                    .put("code", "reviewPending").put("reviewId", entry.snapshot.reviewId()).toJson());
            }
        }
        if (active >= 16 || write.payloadBytes() > MAX_PAYLOAD - retainedPayload)
            return refused("BSL review capacity reached. Apply, reject, or close pending review tabs, then retry.");
        Entry entry = new Entry(write, digest, commit, clock.instant());
        entries.put(entry.snapshot.reviewId(), entry);
        retainedPayload += entry.payload;
        return new Admission(entry.snapshot, false, null);
    }

    private static Admission refused(String message)
    {
        return new Admission(null, false, ToolResult.error(message).toJson());
    }

    public synchronized Snapshot get(String reviewId)
    {
        Entry entry = entries.get(reviewId);
        return entry == null ? null : entry.snapshot;
    }

    public void apply(String reviewId)
    {
        synchronized (this)
        {
            expireDue();
            Entry entry = entries.get(reviewId);
            if (entry == null || entry.snapshot.status() != State.AWAITING_APPROVAL) return;
            if (!accepting || closed) { cancel(entry, "server-stopped"); return; }
            transition(entry, new Outcome(State.APPLYING, false, null, null), null);
        }
        try
        {
            executor.execute(() -> execute(reviewId));
        }
        catch (RuntimeException e)
        {
            failOpening(reviewId, "Cannot schedule the accepted write: " + e.getMessage());
            synchronized (this)
            {
                Entry entry = entries.get(reviewId);
                if (entry != null && entry.snapshot.status() == State.APPLYING && !entry.mutationEntered)
                    transition(entry, new Outcome(State.FAILED, false, null,
                        ToolResult.error("Cannot schedule the accepted write: " + e.getMessage()).toJson()), null);
            }
        }
    }

    private void execute(String id)
    {
        PreparedBslWrite write;
        BiFunction<String, PreparedBslWrite, Outcome> commit;
        synchronized (this)
        {
            Entry entry = entries.get(id);
            if (entry == null || entry.snapshot.status() != State.APPLYING) return;
            write = entry.write;
            commit = entry.commit;
        }
        Outcome outcome;
        try
        {
            outcome = commit.apply(id, write);
            if (outcome == null || !outcome.status().terminal())
                throw new IllegalStateException("Write did not report a terminal outcome");
        }
        catch (Exception e)
        {
            synchronized (this)
            {
                Entry entry = entries.get(id);
                boolean entered = entry != null && entry.mutationEntered;
                outcome = new Outcome(State.FAILED, entered ? null : false, null,
                    (entered ? ToolResult.errorWithUnknownMutationOutcome(e.getMessage())
                        : ToolResult.error(e.getMessage())).toJson());
            }
        }
        synchronized (this)
        {
            Entry entry = entries.get(id);
            if (entry != null && entry.snapshot.status() == State.APPLYING) transition(entry, outcome, null);
        }
    }

    public synchronized boolean tryEnterMutation(String id)
    {
        Entry entry = entries.get(id);
        if (entry == null || entry.snapshot.status() != State.APPLYING || entry.mutationEntered
            || !accepting || closed || projectToken(entry.snapshot.projectName()) != entry.write.projectToken())
            return false;
        entry.mutationEntered = true;
        transition(entry, new Outcome(State.APPLYING, null, null, null), null);
        return true;
    }

    public synchronized void reject(String id)
    {
        expireDue();
        Entry entry = entries.get(id);
        if (entry != null && entry.snapshot.status() == State.AWAITING_APPROVAL)
            transition(entry, new Outcome(State.REJECTED, false, null, null), null);
    }

    public synchronized void failOpening(String id, String message)
    {
        Entry entry = entries.get(id);
        if (entry != null && entry.snapshot.status() == State.AWAITING_APPROVAL)
            transition(entry, new Outcome(State.FAILED, false, null, ToolResult.error(message).toJson()), message);
    }

    public synchronized void expireDue()
    {
        Instant now = clock.instant();
        for (Entry entry : List.copyOf(entries.values()))
            if (entry.snapshot.status() == State.AWAITING_APPROVAL && !now.isBefore(entry.snapshot.expiresAt()))
                transition(entry, new Outcome(State.EXPIRED, false, null, null), "approval-deadline");
    }

    public synchronized void cancelPending(String reason)
    {
        accepting = false;
        for (Entry entry : List.copyOf(entries.values())) cancel(entry, reason);
    }

    private void cancel(Entry entry, String reason)
    {
        if (!entry.snapshot.status().terminal() && !entry.mutationEntered)
            transition(entry, new Outcome(State.CANCELLED, false, null, null), reason);
    }

    public synchronized void beginGeneration()
    {
        if (closed) return;
        cancelPending("server-restarted");
        accepting = true;
    }

    public synchronized AutoCloseable subscribe(String id, Consumer<Snapshot> listener)
    {
        Entry entry = entries.get(id);
        if (entry == null) return () -> { };
        if (!entry.snapshot.status().terminal()) entry.listeners.add(listener);
        listener.accept(entry.snapshot);
        return () -> { synchronized (BslWriteReviews.this) { entry.listeners.remove(listener); } };
    }

    private void transition(Entry entry, Outcome outcome, String reason)
    {
        Snapshot old = entry.snapshot;
        entry.snapshot = new Snapshot(old.reviewId(), outcome.status(), old.projectName(), old.modulePath(),
            old.createdAt(), old.expiresAt(), outcome.status().terminal() ? clock.instant() : null,
            outcome.mutationCommitted(), outcome.resultMarkdown(), outcome.errorJson(), reason);
        if (outcome.status().terminal())
        {
            retainedPayload -= entry.payload;
            entry.write = null;
            entry.commit = null;
        }
        for (Consumer<Snapshot> listener : List.copyOf(entry.listeners))
        {
            try { listener.accept(entry.snapshot); }
            catch (RuntimeException ignored) { /* A disposed observer cannot change the write outcome. */ }
        }
        if (outcome.status().terminal()) entry.listeners.clear();
        long terminal = entries.values().stream().filter(e -> e.snapshot.status().terminal()).count();
        var iterator = entries.values().iterator();
        while (terminal > 100 && iterator.hasNext())
            if (iterator.next().snapshot.status().terminal()) { iterator.remove(); terminal--; }
    }

    private void attachLifecycle()
    {
        workspace = ResourcesPlugin.getWorkspace();
        resourceListener = event -> {
            if (event.getResource() instanceof IProject project) invalidateProject(project.getName());
            if (event.getDelta() != null)
            {
                for (IResourceDelta delta : event.getDelta().getAffectedChildren())
                    if (delta.getResource().getType() == IResource.PROJECT
                        && (delta.getKind() == IResourceDelta.ADDED || delta.getKind() == IResourceDelta.REMOVED
                            || (delta.getFlags() & IResourceDelta.OPEN) != 0))
                        invalidateProject(delta.getResource().getName());
            }
        };
        workspace.addResourceChangeListener(resourceListener,
            IResourceChangeEvent.PRE_CLOSE | IResourceChangeEvent.PRE_DELETE | IResourceChangeEvent.POST_CHANGE);
        expiryJob = new Job("Expire pending BSL write reviews")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                synchronized (BslWriteReviews.this)
                {
                    if (closed) return Status.CANCEL_STATUS;
                    expireDue();
                    schedule(30_000);
                }
                return Status.OK_STATUS;
            }
        };
        expiryJob.setSystem(true);
        expiryJob.schedule(30_000);
    }

    @Override
    public synchronized void close()
    {
        if (closed) return;
        cancelPending("plugin-stopped");
        closed = true;
        if (expiryJob != null) expiryJob.cancel();
        if (workspace != null && resourceListener != null) workspace.removeResourceChangeListener(resourceListener);
        resourceListener = null;
        workspace = null;
    }
}
