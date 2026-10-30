/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import java.io.StringReader;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com._1c.g5.v8.dt.bsl.model.Method;
import org.eclipse.core.runtime.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jgit.diff.*;
import org.eclipse.xtext.*;
import org.eclipse.xtext.nodemodel.*;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.*;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.*;

/** Detached BSL snapshots; only immutable text ranges escape the worker. */
final class MethodAwareBslDiff
{
    static final String ENGINE_PROPERTY = "edt.mcp.bslDiff"; //$NON-NLS-1$
    static final int MAX_CHARS = 524288;
    static final int MAX_LINES = 20000;
    record Token(String type, String text, int start, int length) { }
    record Parsed(IParseResult tree, List<Token> tokens, int bom, List<String> errors) { }
    record Part(String key, int start, int end, List<Token> tokens, int regionBoundaryCount) { }
    record Projection(List<Part> methods, List<Token> outside, String fallback) { }
    record Pair(List<Token> left, List<Token> right) { }
    record Plan(List<Pair> pairs, List<Map<String, Object>> moves, String fallback) { }
    record Changes(List<Token> left, List<Token> right, List<Map<String, Object>> moves) { }
    record Move(String key, List<Span> left, List<Span> right) { }
    record Comparison(Result diff, List<Span> leftMoves, List<Span> rightMoves,
        String fallback, boolean byteChanges, List<Move> moves) { }
    private final IParser parser;
    private final IProgressMonitor monitor;
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

    MethodAwareBslDiff(IParser parser, IProgressMonitor monitor)
    {
        this.parser = parser;
        this.monitor = monitor;
    }

    static Comparison compute(String before, String after, IProgressMonitor monitor) throws Exception
    {
        validateInput(before, after);
        if (monitor.isCanceled()) throw new OperationCanceledException();
        Platform.getBundle("com._1c.g5.v8.dt.bsl").start(org.osgi.framework.Bundle.START_TRANSIENT);
        var provider = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(URI.createURI("memory:/review.bsl"));
        if (provider == null) throw new IllegalStateException("BSL parser is not installed");
        return new MethodAwareBslDiff(provider.get(IParser.class), monitor).compare(before, after);
    }

    static void validateInput(String before, String after)
    {
        for (String text : List.of(before, after))
            if (text.length() > MAX_CHARS || new Document(text).getNumberOfLines() > MAX_LINES)
                throw new IllegalArgumentException("Method-aware comparison limit: 524288 UTF-16 units / 20000 lines per side");
    }

    Comparison compare(String a, String b)
    {
        checkpoint();
        validateInput(a, b);
        Parsed left = parse(a), right = parse(b);
        Plan plan = plan(left, right, a, b);
        Changes changes;
        boolean malformed = !left.errors.isEmpty() || !right.errors.isEmpty();
        if (malformed) changes = textDiff(a, b);
        else
        {
            List<Token> lhs = new ArrayList<>(), rhs = new ArrayList<>();
            for (Pair pair : plan.pairs) diff(pair.left, pair.right, lhs, rhs);
            changes = new Changes(lhs, rhs, plan.moves);
        }
        List<Span> lhs = spans(a, changes.left), rhs = spans(b, changes.right);
        List<Token> lm = new ArrayList<>(), rm = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        for (var move : changes.moves)
        {
            int x = (int)move.get("leftStart"), n = (int)move.get("leftLength");
            int y = (int)move.get("rightStart"), m = (int)move.get("rightLength");
            Token old = new Token("move", a.substring(x, x + n), x, n);
            Token proposed = new Token("move", b.substring(y, y + m), y, m);
            lm.add(old);
            rm.add(proposed);
            moves.add(new Move((String)move.get("key"), spans(a, List.of(old)), spans(b, List.of(proposed))));
        }
        List<Row> rows = align(a, b);
        checkpoint();
        boolean changed = !lhs.isEmpty() || !rhs.isEmpty() || !lm.isEmpty();
        return new Comparison(new Result(malformed ? "Text" : "BSL", changed ? "changed" : "unchanged",
            rows, lhs, rhs), spans(a, lm), spans(b, rm), plan.fallback, !a.equals(b), List.copyOf(moves));
    }

    private void checkpoint()
    {
        if (monitor.isCanceled() || Thread.currentThread().isInterrupted()) throw new OperationCanceledException();
        if (System.nanoTime() >= deadline) throw new IllegalStateException("Method-aware comparison exceeded its two-second budget");
    }

    Parsed parse(String source) {
        checkpoint();
        int bom = source.startsWith("\ufeff") ? 1 : 0;
        // IParser.parse(Reader) itself has no cancellation argument: only boundaries are checked.
        IParseResult result = parser.parse(new StringReader(source.substring(bom)));
        checkpoint();
        List<Token> tokens = new ArrayList<>();
        for (ILeafNode leaf : result.getRootNode().getLeafNodes()) {
            checkpoint();
            if (leaf.isHidden() && leaf.getText().isBlank()) continue;
            tokens.add(new Token(type(leaf), leaf.getText(), leaf.getOffset() + bom, leaf.getLength()));
        }
        List<String> errors = new ArrayList<>();
        for (INode node : result.getSyntaxErrors()) errors.add(node.getOffset() + ": " + node.getSyntaxErrorMessage().getMessage());
        return new Parsed(result, tokens, bom, errors);
    }
    static String type(INode node) {
        EObject element = node.getGrammarElement();
        if (element instanceof RuleCall call) return "rule:" + call.getRule().getName();
        if (element instanceof AbstractRule rule) return "rule:" + rule.getName();
        return element == null ? "unknown" : element.eClass().getName();
    }

    Projection project(Parsed p, String source) {
        // Conservative guard: do not infer method ownership through conditional compilation.
        // This may also reject directive-looking text in a string; flat fallback preserves it.
        List<Integer> regionBoundaries = new ArrayList<>(); int lineOffset = 0;
        for (String line : source.split("(?<=\\n)|(?<=\\r)(?!\\n)", -1)) {
            String t = (lineOffset == 0 && p.bom == 1 ? line.substring(1) : line).stripLeading().toLowerCase(Locale.ROOT);
            if (t.startsWith("#") && !(t.startsWith("#\u043e\u0431\u043b\u0430\u0441\u0442\u044c") || t.startsWith("#\u043a\u043e\u043d\u0435\u0446\u043e\u0431\u043b\u0430\u0441\u0442\u0438")
                    || t.startsWith("#region") || t.startsWith("#endregion")))
                return new Projection(List.of(), p.tokens, "preprocessor:flat-tokens");
            if (t.startsWith("#")) regionBoundaries.add(lineOffset);
            lineOffset += line.length();
        }
        List<Part> parts = new ArrayList<>();
        EObject root = p.tree.getRootASTElement();
        if (root != null) for (var it = root.eAllContents(); it.hasNext();) {
            checkpoint(); EObject next = it.next();
            if (!(next instanceof Method method)) continue;
            ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
            if (node == null || method.getName() == null) return new Projection(List.of(), p.tokens, "missing-identity:flat-tokens");
            int start = node.getOffset() + p.bom, end = start + node.getLength();
            parts.add(new Part(method.eClass().getName() + ":" + method.getName().toLowerCase(Locale.ROOT), start, end, List.of(), 0));
            it.prune();
        }
        parts.sort(Comparator.comparingInt(Part::start));
        Set<String> keys = new HashSet<>(); int cursor = 0, tokenIndex = 0, regionIndex = 0;
        List<Token> outside = new ArrayList<>(); List<Part> filled = new ArrayList<>();
        for (Part part : parts) {
            if (!keys.add(part.key) || part.start < cursor) return new Projection(List.of(), p.tokens, "ambiguous-identity:flat-tokens");
            while (tokenIndex < p.tokens.size() && p.tokens.get(tokenIndex).start < part.start) outside.add(p.tokens.get(tokenIndex++));
            int first = tokenIndex;
            while (tokenIndex < p.tokens.size() && p.tokens.get(tokenIndex).start < part.end) tokenIndex++;
            while (regionIndex < regionBoundaries.size() && regionBoundaries.get(regionIndex) < part.start) regionIndex++;
            filled.add(new Part(part.key, part.start, part.end, p.tokens.subList(first, tokenIndex), regionIndex));
            cursor = part.end;
        }
        outside.addAll(p.tokens.subList(tokenIndex, p.tokens.size()));
        return new Projection(filled, outside, null);
    }

    Plan plan(Parsed left, Parsed right, String a, String b) {
        if (!left.errors.isEmpty() || !right.errors.isEmpty()) return new Plan(List.of(), List.of(), "syntax-errors:text-lines");
        Projection l = project(left, a), r = project(right, b);
        if (l.fallback != null || r.fallback != null) return new Plan(List.of(new Pair(left.tokens, right.tokens)), List.of(),
            l.fallback != null ? l.fallback : r.fallback);
        Map<String, Part> lm = new LinkedHashMap<>(), rm = new LinkedHashMap<>();
        for (Part p : l.methods) lm.put(p.key, p); for (Part p : r.methods) rm.put(p.key, p);
        for (String key : lm.keySet()) if (rm.containsKey(key) && lm.get(key).regionBoundaryCount != rm.get(key).regionBoundaryCount)
            return new Plan(List.of(new Pair(left.tokens, right.tokens)), List.of(), "region-placement:flat-tokens");
        List<Pair> pairs = new ArrayList<>(); pairs.add(new Pair(l.outside, r.outside));
        Set<String> all = new LinkedHashSet<>(lm.keySet()); all.addAll(rm.keySet());
        for (String key : all) pairs.add(new Pair(lm.containsKey(key) ? lm.get(key).tokens : List.of(),
                                                  rm.containsKey(key) ? rm.get(key).tokens : List.of()));
        List<Token> orderA = new ArrayList<>(), orderB = new ArrayList<>();
        for (Part p : l.methods) if (rm.containsKey(p.key)) orderA.add(new Token("method", p.key, 0, 0));
        for (Part p : r.methods) if (lm.containsKey(p.key)) orderB.add(new Token("method", p.key, 0, 0));
        List<Token> removed = new ArrayList<>(), added = new ArrayList<>(); diff(orderA, orderB, removed, added);
        Set<String> inserted = new HashSet<>(); for (Token t : added) inserted.add(t.text);
        List<Map<String, Object>> moves = new ArrayList<>();
        for (Token t : removed) if (inserted.contains(t.text)) {
            Part x = lm.get(t.text), y = rm.get(t.text);
            moves.add(Map.of("key", t.text, "leftStart", x.start, "leftLength", x.end - x.start,
                "rightStart", y.start, "rightLength", y.end - y.start));
        }
        return new Plan(pairs, moves, null);
    }

    static class Tokens extends Sequence {
        final List<Token> list;
        Tokens(List<Token> list) { this.list = list; }
        @Override public int size() { return list.size(); }
    }
    final SequenceComparator<Tokens> COMPARATOR = new SequenceComparator<>() {
        @Override public boolean equals(Tokens a, int i, Tokens b, int j) {
            checkpoint(); Token x = a.list.get(i), y = b.list.get(j);
            return x.type.equals(y.type) && x.text.equals(y.text);
        }
        @Override public int hash(Tokens a, int i) { checkpoint(); Token t = a.list.get(i); return Objects.hash(t.type, t.text); }
    };
    void diff(List<Token> a, List<Token> b, List<Token> left, List<Token> right) {
        checkpoint();
        for (Edit e : new HistogramDiff().diff(COMPARATOR, new Tokens(a), new Tokens(b))) {
            left.addAll(a.subList(e.getBeginA(), e.getEndA())); right.addAll(b.subList(e.getBeginB(), e.getEndB()));
        }
    }
    static List<Token> textLines(String source) {
        List<Token> tokens = new ArrayList<>();
        int start = 0;
        for (String line : source.split("(?<=\\n)|(?<=\\r)(?!\\n)", -1)) {
            if (!line.isEmpty()) tokens.add(new Token("line", line, start, line.length()));
            start += line.length();
        }
        return tokens;
    }
    Changes textDiff(String a, String b) {
        List<Token> l = new ArrayList<>(), r = new ArrayList<>(); diff(textLines(a), textLines(b), l, r);
        return new Changes(l, r, List.of());
    }

    static List<Span> spans(String source, List<Token> tokens)
    {
        Document document = new Document(source);
        List<Span> result = new ArrayList<>();
        try
        {
            for (Token token : tokens)
            {
                if (token.start < 0 || token.length < 0 || token.start + token.length > source.length()
                    || !source.regionMatches(token.start, token.text, 0, token.length))
                    throw new IllegalArgumentException("Token range does not match the snapshot");
                if (token.length == 0) continue;
                int last = document.getLineOfOffset(token.start + token.length - 1);
                for (int line = document.getLineOfOffset(token.start); line <= last; line++)
                {
                    var info = document.getLineInformation(line);
                    int start = Math.min(info.getLength(), Math.max(0, token.start - info.getOffset()));
                    int end = Math.min(info.getLength(), token.start + token.length - info.getOffset());
                    result.add(new Span(line, start, Math.max(0, end - start)));
                }
            }
        }
        catch (BadLocationException e) { throw new IllegalArgumentException("Invalid source range", e); }
        result.sort(Comparator.comparingInt(Span::line).thenComparingInt(Span::start));
        return List.copyOf(result);
    }

    private static final class TextLines extends Sequence
    {
        final List<String> lines;
        TextLines(String source) { lines = StructuralBslDiff.lines(source); }
        @Override public int size() { return lines.size(); }
    }

    List<Row> align(String a, String b)
    {
        TextLines left = new TextLines(a), right = new TextLines(b);
        SequenceComparator<TextLines> comparator = new SequenceComparator<>() {
            @Override public boolean equals(TextLines x, int i, TextLines y, int j)
            { checkpoint(); return x.lines.get(i).equals(y.lines.get(j)); }
            @Override public int hash(TextLines x, int i) { checkpoint(); return x.lines.get(i).hashCode(); }
        };
        List<Row> rows = new ArrayList<>();
        int x = 0, y = 0;
        for (Edit edit : new HistogramDiff().diff(comparator, left, right))
        {
            checkpoint();
            while (x < edit.getBeginA() && y < edit.getBeginB()) rows.add(new Row(x++, y++));
            while (x < edit.getEndA() || y < edit.getEndB())
                rows.add(new Row(x < edit.getEndA() ? x++ : null, y < edit.getEndB() ? y++ : null));
        }
        while (x < left.size() || y < right.size())
        { checkpoint(); rows.add(new Row(x < left.size() ? x++ : null, y < right.size() ? y++ : null)); }
        return List.copyOf(rows);
    }
}
