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
    record Part(String key, int start, int end, List<Token> tokens, List<String> context) { }
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
            Document sourceA = new Document(a), sourceB = new Document(b);
            for (Pair pair : plan.pairs) diffLines(pair.left, pair.right, sourceA, sourceB, lhs, rhs);
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
        List<Part> parts = new ArrayList<>();
        Document document = new Document(source);
        EObject root = p.tree.getRootASTElement();
        if (root != null) for (var it = root.eAllContents(); it.hasNext();) {
            checkpoint(); EObject next = it.next();
            if (!(next instanceof Method method)) continue;
            ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
            if (node == null || method.getName() == null) return new Projection(List.of(), p.tokens, "missing-identity:flat-tokens");
            int start = node.getOffset() + p.bom, end = start + node.getLength();
            start = documentationStart(document, source, start);
            parts.add(new Part(method.eClass().getName() + ":" + method.getName().toLowerCase(Locale.ROOT), start, end, List.of(), List.of()));
            it.prune();
        }
        parts.sort(Comparator.comparingInt(Part::start));
        Set<String> keys = new HashSet<>(); int cursor = 0, tokenIndex = 0;
        List<Token> outside = new ArrayList<>(); List<Part> filled = new ArrayList<>();
        NavigableMap<Integer, List<String>> contexts = contexts(p, source);
        for (Part part : parts) {
            if (!keys.add(part.key) || part.start < cursor) return new Projection(List.of(), p.tokens, "ambiguous-identity:flat-tokens");
            while (tokenIndex < p.tokens.size() && p.tokens.get(tokenIndex).start < part.start) outside.add(p.tokens.get(tokenIndex++));
            int first = tokenIndex;
            while (tokenIndex < p.tokens.size() && p.tokens.get(tokenIndex).start < part.end) tokenIndex++;
            filled.add(new Part(part.key, part.start, part.end, p.tokens.subList(first, tokenIndex),
                contexts.floorEntry(part.start).getValue()));
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
        List<Pair> pairs = new ArrayList<>(); pairs.add(new Pair(l.outside, r.outside));
        Set<String> all = new LinkedHashSet<>(lm.keySet()); all.addAll(rm.keySet());
        for (String key : all) pairs.add(new Pair(lm.containsKey(key) ? lm.get(key).tokens : List.of(),
                                                  rm.containsKey(key) ? rm.get(key).tokens : List.of()));
        // Context transfers already explain an order change; do not also accuse the stable neighbour.
        Set<String> moved = new LinkedHashSet<>();
        for (String key : lm.keySet())
            if (rm.containsKey(key) && !lm.get(key).context.equals(rm.get(key).context)) moved.add(key);
        List<Token> orderA = new ArrayList<>(), orderB = new ArrayList<>();
        for (Part p : l.methods) if (rm.containsKey(p.key) && !moved.contains(p.key)) orderA.add(new Token("method", p.key, 0, 0));
        for (Part p : r.methods) if (lm.containsKey(p.key) && !moved.contains(p.key)) orderB.add(new Token("method", p.key, 0, 0));
        List<Token> removed = new ArrayList<>(), added = new ArrayList<>(); diff(orderA, orderB, removed, added);
        Set<String> inserted = new HashSet<>(); for (Token t : added) inserted.add(t.text);
        for (Token t : removed) if (inserted.contains(t.text)) moved.add(t.text);
        List<Map<String, Object>> moves = new ArrayList<>();
        for (String key : moved) {
            Part x = lm.get(key), y = rm.get(key);
            moves.add(Map.of("key", key, "leftStart", x.start, "leftLength", x.end - x.start,
                "rightStart", y.start, "rightLength", y.end - y.start));
        }
        return new Plan(pairs, moves, null);
    }

    private static int documentationStart(Document document, String source, int start)
    {
        try
        {
            int line = document.getLineOfOffset(start);
            for (int previous = line - 1; previous >= 0; previous--)
            {
                var info = document.getLineInformation(previous);
                if (!source.substring(info.getOffset(), info.getOffset() + info.getLength()).stripLeading().startsWith("//")) break;
                start = info.getOffset();
            }
            return start;
        }
        catch (BadLocationException e) { throw new IllegalArgumentException("Invalid documentation range", e); }
    }

    /** Only lexer-backed directive starts count; comments and multiline strings are excluded. */
    private NavigableMap<Integer, List<String>> contexts(Parsed parsed, String source)
    {
        NavigableMap<Integer, List<String>> contexts = new TreeMap<>();
        contexts.put(0, List.of());
        List<String> regions = new ArrayList<>();
        List<List<String>> branches = new ArrayList<>();
        int previousLine = -1;
        Document document = new Document(source);
        try
        {
            for (Token token : parsed.tokens)
            {
                checkpoint();
                if (!token.text.startsWith("#")) continue;
                int line = document.getLineOfOffset(token.start);
                if (line == previousLine) continue;
                var info = document.getLineInformation(line);
                if (!source.substring(info.getOffset(), token.start).isBlank()) continue;
                previousLine = line;
                String directive = source.substring(token.start, info.getOffset() + info.getLength())
                    .replaceFirst("//.*$", "").strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                String keyword = directive.split(" ", 2)[0];
                switch (keyword)
                {
                    case "#область", "#region" -> regions.add(directive.substring(keyword.length()).strip());
                    case "#конецобласти", "#endregion" -> { if (!regions.isEmpty()) regions.remove(regions.size() - 1); }
                    case "#если", "#if" -> branches.add(List.of(directive, directive));
                    case "#иначе", "#else", "#иначеесли", "#elsif", "#elseif" -> {
                        if (!branches.isEmpty())
                        {
                            int last = branches.size() - 1;
                            branches.set(last, List.of(branches.get(last).get(0), directive));
                        }
                    }
                    case "#конецесли", "#endif" -> { if (!branches.isEmpty()) branches.remove(branches.size() - 1); }
                    default -> { }
                }
                List<String> context = new ArrayList<>();
                for (String region : regions) context.add("region:" + region);
                for (List<String> branch : branches) context.add("branch:" + branch);
                contexts.put(info.getOffset() + info.getLength(), List.copyOf(context));
            }
        }
        catch (BadLocationException e) { throw new IllegalArgumentException("Invalid directive range", e); }
        return contexts;
    }

    /** Anchor whole unchanged lines before refining tokens, so repeated keywords cannot leak across statements. */
    private void diffLines(List<Token> a, List<Token> b, Document sourceA, Document sourceB, List<Token> left, List<Token> right)
    {
        TokenLines l = new TokenLines(a, sourceA), r = new TokenLines(b, sourceB);
        SequenceComparator<TokenLines> comparator = new SequenceComparator<>() {
            @Override public boolean equals(TokenLines x, int i, TokenLines y, int j)
            { checkpoint(); return x.keys.get(i).equals(y.keys.get(j)); }
            @Override public int hash(TokenLines x, int i) { checkpoint(); return x.keys.get(i).hashCode(); }
        };
        for (Edit edit : new HistogramDiff().diff(comparator, l, r))
        {
            List<Token> lhs = new ArrayList<>(), rhs = new ArrayList<>();
            for (int i = edit.getBeginA(); i < edit.getEndA(); i++) lhs.addAll(l.lines.get(i));
            for (int i = edit.getBeginB(); i < edit.getEndB(); i++) rhs.addAll(r.lines.get(i));
            diff(lhs, rhs, left, right);
        }
    }

    private final class TokenLines extends Sequence
    {
        final List<List<Token>> lines = new ArrayList<>();
        final List<List<String>> keys = new ArrayList<>();
        TokenLines(List<Token> tokens, Document document)
        {
            List<Token> line = new ArrayList<>();
            int previous = -1;
            try
            {
                for (Token token : tokens)
                {
                    checkpoint();
                    for (Token part : splitLines(token))
                    {
                        int number = document.getLineOfOffset(part.start);
                        if (number != previous) { append(line); line = new ArrayList<>(); }
                        line.add(part); previous = number;
                    }
                }
            }
            catch (BadLocationException e) { throw new IllegalArgumentException("Invalid token line", e); }
            append(line);
        }
        private void append(List<Token> line)
        {
            if (line.isEmpty()) return;
            lines.add(List.copyOf(line));
            List<String> key = new ArrayList<>();
            for (Token token : line) { key.add(token.type); key.add(token.text); }
            keys.add(List.copyOf(key));
        }
        @Override public int size() { return lines.size(); }
    }

    private static List<Token> splitLines(Token token)
    {
        List<Token> result = new ArrayList<>();
        int offset = 0;
        for (String line : token.text.split("(?<=\\n)|(?<=\\r)(?!\\n)", -1))
        {
            int content = line.length();
            while (content > 0 && (line.charAt(content - 1) == '\r' || line.charAt(content - 1) == '\n')) content--;
            if (content > 0) result.add(new Token(token.type, line.substring(0, content), token.start + offset, content));
            offset += line.length();
        }
        return result;
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
            if (e.getLengthA() == 1 && e.getLengthB() == 1)
            {
                Token x = a.get(e.getBeginA()), y = b.get(e.getBeginB());
                if (x.length > 0 && y.length > 0 && x.type.equals(y.type))
                {
                    int prefix = 0, suffix = 0;
                    while (prefix < x.text.length() && prefix < y.text.length()
                        && x.text.codePointAt(prefix) == y.text.codePointAt(prefix))
                        prefix += Character.charCount(x.text.codePointAt(prefix));
                    while (suffix < x.text.length() - prefix && suffix < y.text.length() - prefix
                        && x.text.codePointBefore(x.text.length() - suffix) == y.text.codePointBefore(y.text.length() - suffix))
                        suffix += Character.charCount(x.text.codePointBefore(x.text.length() - suffix));
                    fragment(left, x, prefix, x.text.length() - suffix);
                    fragment(right, y, prefix, y.text.length() - suffix);
                    continue;
                }
            }
            left.addAll(a.subList(e.getBeginA(), e.getEndA())); right.addAll(b.subList(e.getBeginB(), e.getEndB()));
        }
    }
    private static void fragment(List<Token> target, Token token, int start, int end)
    {
        if (end > start) target.add(new Token(token.type, token.text.substring(start, end), token.start + start, end - start));
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
