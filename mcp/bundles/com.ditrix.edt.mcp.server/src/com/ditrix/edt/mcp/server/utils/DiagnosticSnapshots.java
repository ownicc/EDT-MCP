/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/** Bounded, process-local diagnostic snapshots and repeatable signature-count comparison. */
public final class DiagnosticSnapshots
{
    public static final int MAX_SNAPSHOTS = 10;
    public static final int MAX_PROBLEMS = 10_000;

    private static final DiagnosticSnapshots SHARED = new DiagnosticSnapshots();

    /** Normalized filter scope. Module filters share the listing tools' path semantics. */
    public record Scope(String projectName, String severity, String checkId, List<String> modulePaths)
    {
        public Scope
        {
            Objects.requireNonNull(projectName);
            severity = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT); //$NON-NLS-1$
            checkId = checkId == null ? "" : checkId.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
            modulePaths = List.copyOf(new TreeSet<>(MarkerRows.modulePathKeys(modulePaths)));
        }
    }

    /** Copied values only: no live marker, project, model object or mutable row survives capture. */
    public record Diagnostic(String modulePath, String location, String checkId, String contributorId,
        String severity, String message, Integer line, Integer offset, Integer length)
    {
        public Diagnostic
        {
            Objects.requireNonNull(checkId);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(message);
        }
    }

    public record Snapshot(String id, Scope scope, Instant capturedAt, List<Diagnostic> problems)
    {
        public Snapshot
        {
            problems = List.copyOf(problems);
        }
    }

    /** Only remaining problems carry both sides; fixed/new carry their observed side. */
    public record Change(String status, Diagnostic before, Diagnostic after)
    {
    }

    public record Comparison(int added, int fixed, int remaining, int total, int limit, int offset,
        List<Change> problems)
    {
        public Comparison
        {
            problems = List.copyOf(problems);
        }

        public Map<String, Integer> counts()
        {
            return Map.of("new", added, "fixed", fixed, "remaining", remaining); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    // Typed fields prevent delimiter-containing messages and IDs from aliasing another signature.
    private record Signature(String modulePath, String location, String checkId, String contributorId,
        String severity, String message)
    {
        static Signature of(Diagnostic problem)
        {
            String module = MarkerRows.normalizeModulePath(problem.modulePath());
            return new Signature(module == null ? null : module.toLowerCase(Locale.ROOT),
                module == null ? problem.location() : null, problem.checkId(), problem.contributorId(),
                problem.severity(), problem.message());
        }
    }

    private static final Comparator<Diagnostic> ORDER = Comparator
        .comparing((Diagnostic p) -> text(Signature.of(p).modulePath()))
        .thenComparing(p -> text(Signature.of(p).location()))
        .thenComparing(Diagnostic::checkId)
        .thenComparing(p -> text(p.contributorId()))
        .thenComparing(Diagnostic::severity)
        .thenComparing(Diagnostic::message)
        .thenComparing(Diagnostic::line, Comparator.nullsFirst(Integer::compareTo))
        .thenComparing(Diagnostic::offset, Comparator.nullsFirst(Integer::compareTo))
        .thenComparing(Diagnostic::length, Comparator.nullsFirst(Integer::compareTo))
        .thenComparing(p -> text(p.location()))
        .thenComparing(p -> text(p.modulePath()));

    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();

    public static DiagnosticSnapshots shared()
    {
        return SHARED;
    }

    /** Store only a successful complete capture; a refusal never evicts an existing snapshot. */
    public synchronized Snapshot capture(Scope scope, List<Diagnostic> problems)
    {
        if (problems.size() > MAX_PROBLEMS)
        {
            throw new IllegalArgumentException("Snapshot exceeds " + MAX_PROBLEMS //$NON-NLS-1$
                + " matching problems. Narrow severity, checkId or modulePaths and capture again."); //$NON-NLS-1$
        }
        List<Diagnostic> sorted = new ArrayList<>(problems);
        sorted.sort(ORDER);
        Snapshot snapshot = new Snapshot(UUID.randomUUID().toString(), Objects.requireNonNull(scope),
            Instant.now(), sorted);
        snapshots.put(snapshot.id(), snapshot);
        if (snapshots.size() > MAX_SNAPSHOTS)
        {
            snapshots.remove(snapshots.keySet().iterator().next());
        }
        return snapshot;
    }

    public synchronized Snapshot require(String id)
    {
        Snapshot snapshot = snapshots.get(id);
        if (snapshot == null)
        {
            throw new IllegalArgumentException("Unknown or evicted snapshotId '" + id //$NON-NLS-1$
                + "'. Use problem_snapshots(action='capture') again; snapshots are lost on EDT " //$NON-NLS-1$
                + "restart and only the last " + MAX_SNAPSHOTS + " successful captures are retained."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return snapshot;
    }

    /** Capture both references under one lock so eviction cannot split a comparison admission. */
    public Comparison compare(String baselineId, String snapshotId, int limit, int offset)
    {
        Snapshot before;
        Snapshot after;
        synchronized (this)
        {
            before = require(baselineId);
            after = require(snapshotId);
        }
        return compare(before, after, limit, offset);
    }

    /** Compare admitted immutable references even if retention evicts their IDs meanwhile. */
    public static Comparison compare(Snapshot before, Snapshot after, int limit, int offset)
    {
        if (!before.scope().equals(after.scope()))
        {
            throw new IllegalArgumentException("Snapshot scope mismatch between baselineId '" + before.id() //$NON-NLS-1$
                + "' and snapshotId '" + after.id() //$NON-NLS-1$
                + "'. Capture both with the same projectName, severity, checkId and modulePaths."); //$NON-NLS-1$
        }
        if (offset < 0)
        {
            throw new IllegalArgumentException("offset must be a nonnegative integer; use 0 for the first page."); //$NON-NLS-1$
        }

        Map<Signature, Deque<Diagnostic>> unmatched = new LinkedHashMap<>();
        for (Diagnostic problem : before.problems())
        {
            unmatched.computeIfAbsent(Signature.of(problem), key -> new ArrayDeque<>()).add(problem);
        }
        List<Change> added = new ArrayList<>();
        List<Change> remaining = new ArrayList<>();
        for (Diagnostic problem : after.problems())
        {
            Deque<Diagnostic> candidates = unmatched.get(Signature.of(problem));
            if (candidates == null || candidates.isEmpty())
            {
                added.add(new Change("new", null, problem)); //$NON-NLS-1$
            }
            else
            {
                remaining.add(new Change("remaining", candidates.removeFirst(), problem)); //$NON-NLS-1$
            }
        }
        List<Change> fixed = new ArrayList<>();
        unmatched.values().forEach(group -> group.forEach(problem ->
            fixed.add(new Change("fixed", problem, null)))); //$NON-NLS-1$
        fixed.sort(Comparator.comparing(Change::before, ORDER));

        List<Change> all = new ArrayList<>(added);
        all.addAll(fixed);
        all.addAll(remaining);
        int effectiveLimit = Pagination.clampLimit(limit, Pagination.MAX_LIMIT);
        int start = Math.min(offset, all.size());
        int end = (int)Math.min((long)start + effectiveLimit, all.size());
        return new Comparison(added.size(), fixed.size(), remaining.size(), all.size(), effectiveLimit,
            offset, all.subList(start, end));
    }

    private static String text(String value)
    {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
