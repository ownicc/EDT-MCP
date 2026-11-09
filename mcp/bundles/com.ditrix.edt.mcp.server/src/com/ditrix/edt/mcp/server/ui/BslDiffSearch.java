/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.FindReplaceDocumentAdapter;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Row;

/** Literal search on original snapshots, with coordinates in the aligned display. */
final class BslDiffSearch
{
    enum Scope { OLD, NEW, BOTH }
    record Match(boolean left, int row, int column, int length) { }

    private BslDiffSearch() { }

    static List<Match> find(String before, String after, List<Row> rows, String query,
        Scope scope, boolean matchCase)
    {
        if (query.isEmpty()) return List.of();
        List<Match> matches = new ArrayList<>();
        try
        {
            if (scope != Scope.NEW) collect(before, rows, query, matchCase, true, matches);
            if (scope != Scope.OLD) collect(after, rows, query, matchCase, false, matches);
        }
        catch (BadLocationException e) { throw new IllegalStateException("Invalid snapshot search coordinates", e); } //$NON-NLS-1$
        matches.sort(Comparator.comparingInt(Match::row).thenComparing(match -> !match.left()).thenComparingInt(Match::column));
        return List.copyOf(matches);
    }

    private static void collect(String source, List<Row> rows, String query, boolean matchCase,
        boolean left, List<Match> matches) throws BadLocationException
    {
        Document document = new Document(source);
        int[] displayRows = new int[document.getNumberOfLines()];
        for (int row = 0; row < rows.size(); row++)
        {
            Integer original = left ? rows.get(row).left() : rows.get(row).right();
            if (original != null) displayRows[original] = row;
        }
        var finder = new FindReplaceDocumentAdapter(document);
        for (int offset = 0; offset < source.length();)
        {
            var found = finder.find(offset, query, true, matchCase, false, false);
            if (found == null) break;
            int line = document.getLineOfOffset(found.getOffset());
            matches.add(new Match(left, displayRows[line], found.getOffset() - document.getLineOffset(line), found.getLength()));
            offset = found.getOffset() + found.getLength();
        }
    }

    static int next(int current, int count, boolean backward)
    {
        if (count == 0) return -1;
        return current < 0 ? (backward ? count - 1 : 0) : Math.floorMod(current + (backward ? -1 : 1), count);
    }
}
