/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import java.util.*;
import com.ditrix.edt.mcp.server.ui.MethodAwareBslDiff.MethodChange;
import com.ditrix.edt.mcp.server.ui.MethodAwareBslDiff.MethodKind;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Result;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Span;

/** Navigation is a projection of the rendered diff, never a second comparison. */
final class BslMethodNavigation
{
    record Entry(MethodChange method, int row, Set<Integer> changedRows, int removed, int added, String fallback)
    {
        String name()
        {
            return method != null ? method.name()
                : fallback == null ? Messages.BslDiff_OutsideMethods : Messages.BslDiff_NavigationFallback;
        }
    }

    private BslMethodNavigation() { }

    static boolean canCompare(MethodChange method)
    {
        return method != null && !method.left().isEmpty() && !method.right().isEmpty();
    }

    static List<Entry> entries(Result diff, List<MethodChange> methods, String fallback)
    {
        Map<Integer, Integer> leftRows = new HashMap<>(), rightRows = new HashMap<>();
        for (int i = 0; i < diff.rows().size(); i++)
        {
            var row = diff.rows().get(i);
            if (row.left() != null) leftRows.put(row.left(), i);
            if (row.right() != null) rightRows.put(row.right(), i);
        }
        Set<Integer> removed = lines(diff.left()), added = lines(diff.right());
        if (fallback != null)
        {
            var entry = entry(null, removed, added, leftRows, rightRows, fallback);
            return entry.row() < 0 ? List.of() : List.of(entry);
        }
        List<Entry> entries = new ArrayList<>();
        Set<Integer> outsideLeft = new HashSet<>(removed), outsideRight = new HashSet<>(added);
        for (var method : methods)
        {
            Set<Integer> a = lines(method.left()), b = lines(method.right());
            outsideLeft.removeAll(a);
            outsideRight.removeAll(b);
            a.retainAll(removed);
            b.retainAll(added);
            Entry entry = entry(method, a, b, leftRows, rightRows, null);
            if (entry.row() >= 0) entries.add(entry);
        }
        Entry outside = entry(null, outsideLeft, outsideRight, leftRows, rightRows, null);
        if (outside.row() >= 0) entries.add(outside);
        entries.sort(Comparator.comparingInt(Entry::row));
        return List.copyOf(entries);
    }

    private static Entry entry(MethodChange method, Set<Integer> a, Set<Integer> b,
        Map<Integer, Integer> leftRows, Map<Integer, Integer> rightRows, String fallback)
    {
        Set<Integer> changed = new TreeSet<>();
        for (int line : a) changed.add(leftRows.get(line));
        for (int line : b) changed.add(rightRows.get(line));
        // Prefer a real edit on the proposed side, then a removal, then a move/formatting header.
        int row = b.stream().mapToInt(rightRows::get).min()
            .orElseGet(() -> a.stream().mapToInt(leftRows::get).min().orElse(-1));
        if (row < 0 && method != null)
        {
            var endpoint = method.right().isEmpty() ? method.left() : method.right();
            var rows = method.right().isEmpty() ? leftRows : rightRows;
            if (!endpoint.isEmpty()) row = rows.get(endpoint.get(0).line());
        }
        return new Entry(method, row, Set.copyOf(changed), a.size(), b.size(), fallback);
    }

    private static Set<Integer> lines(List<Span> spans)
    {
        Set<Integer> lines = new HashSet<>();
        for (Span span : spans) lines.add(span.line());
        return lines;
    }

    static boolean matches(Entry entry, String query, Set<MethodKind> kinds)
    {
        if (!entry.name().toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT))) return false;
        MethodKind kind = entry.method() == null ? MethodKind.CHANGED : entry.method().kind();
        return kinds.contains(kind) || kind == MethodKind.MOVED_CHANGED
            && (kinds.contains(MethodKind.MOVED) || kinds.contains(MethodKind.CHANGED));
    }
}
