package experiment;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.AbstractRule;
import org.eclipse.xtext.RuleCall;
import org.eclipse.xtext.nodemodel.*;
import org.eclipse.xtext.parser.*;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.jgit.diff.*;

import com.google.gson.*;
import com.github.gumtreediff.tree.*;
import com.github.gumtreediff.matchers.*;
import com.github.gumtreediff.actions.*;

/** Throwaway headless Equinox application. Never loads or saves workspace source. */
public class Probe implements IApplication {
    record Token(String type, String text, int start, int length) { }
    record Parsed(IParseResult result, List<Token> tokens, int offset, List<String> errors) { }
    private IParser parser;

    @Override public Object start(IApplicationContext context) throws Exception {
        Platform.getBundle("com._1c.g5.v8.dt.bsl").start();
        var provider = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(URI.createURI("memory:/probe.bsl"));
        if (provider == null) throw new IllegalStateException("No BSL language provider");
        parser = provider.get(IParser.class);
        var input = JsonParser.parseString(Files.readString(Path.of(System.getProperty("probe.input"))));
        JsonArray output = new JsonArray();
        for (var value : input.getAsJsonArray()) {
            JsonObject item = value.getAsJsonObject();
            String before = item.get("before").getAsString(), after = item.get("after").getAsString();
            JsonObject row = new JsonObject();
            row.addProperty("id", item.get("id").getAsString());
            long start = System.nanoTime();
            Parsed left = parse(before), right = parse(after);
            row.addProperty("parseMs", (System.nanoTime() - start) / 1e6);
            row.add("leftErrors", new Gson().toJsonTree(left.errors));
            row.add("rightErrors", new Gson().toJsonTree(right.errors));
            row.addProperty("leftTokens", left.tokens.size());
            row.addProperty("rightTokens", right.tokens.size());
            row.addProperty("detached", left.result.getRootASTElement() != null
                && right.result.getRootASTElement() != null
                && left.result.getRootASTElement().eResource() == null
                && right.result.getRootASTElement().eResource() == null);
            row.addProperty("requiresFallback", !left.errors.isEmpty() || !right.errors.isEmpty());
            for (String engine : System.getProperty("probe.engines", "jgit,gumtree").split(",")) {
                if (engine.isBlank()) continue;
                try {
                    JsonObject result = null;
                    double[] timings = new double[3];
                    for (int i = 0; i < timings.length; i++) {
                        start = System.nanoTime();
                        result = engine.equals("jgit") ? tokenDiff(left, right) : treeDiff(left, right);
                        timings[i] = (System.nanoTime() - start) / 1e6;
                    }
                    Arrays.sort(timings);
                    result.addProperty("medianDiffMs", timings[1]);
                    row.add(engine, result);
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.toString());
                    row.add(engine, error);
                }
            }
            output.add(row);
            // Flush each case, so a bounded failed run retains the preceding evidence.
            Files.writeString(Path.of(System.getProperty("probe.output")),
                new GsonBuilder().setPrettyPrinting().create().toJson(output));
            System.out.println("PROBE " + row.get("id") + " parseErrors="
                + (left.errors.size() + right.errors.size()));
        }
        JsonObject memory = new JsonObject();
        var bean = java.lang.management.ManagementFactory.getMemoryMXBean();
        memory.addProperty("heapUsedBeforeGc", bean.getHeapMemoryUsage().getUsed());
        System.gc();
        memory.addProperty("heapUsedAfterRequestedGc", bean.getHeapMemoryUsage().getUsed());
        memory.addProperty("heapLimit", bean.getHeapMemoryUsage().getMax());
        Files.writeString(Path.of(System.getProperty("probe.output") + ".memory.json"), memory.toString());
        return EXIT_OK;
    }

    private Parsed parse(String source) {
        int offset = source.startsWith("\ufeff") ? 1 : 0;
        IParseResult result = parser.parse(new StringReader(source.substring(offset)));
        if (result == null || result.getRootNode() == null) throw new IllegalStateException("No parse tree");
        List<Token> tokens = new ArrayList<>();
        for (ILeafNode leaf : result.getRootNode().getLeafNodes()) {
            if (leaf.isHidden() && leaf.getText().isBlank()) continue;
            tokens.add(new Token(type(leaf), leaf.getText(), leaf.getOffset() + offset, leaf.getLength()));
        }
        List<String> errors = new ArrayList<>();
        for (INode node : result.getSyntaxErrors())
            errors.add(node.getOffset() + ": " + node.getSyntaxErrorMessage().getMessage());
        return new Parsed(result, tokens, offset, errors);
    }

    private static String type(INode node) {
        EObject element = node.getGrammarElement();
        if (element instanceof RuleCall call) return "rule:" + call.getRule().getName();
        if (element instanceof AbstractRule rule) return "rule:" + rule.getName();
        return element == null ? "unknown" : element.eClass().getName();
    }

    private static class Tokens extends Sequence {
        final List<Token> list;
        Tokens(List<Token> list) { this.list = list; }
        @Override public int size() { return list.size(); }
    }
    private static final SequenceComparator<Tokens> COMPARATOR = new SequenceComparator<>() {
        @Override public boolean equals(Tokens a, int i, Tokens b, int j) {
            return a.list.get(i).text.equals(b.list.get(j).text)
                && a.list.get(i).type.equals(b.list.get(j).type);
        }
        @Override public int hash(Tokens a, int i) {
            return Objects.hash(a.list.get(i).type, a.list.get(i).text);
        }
    };
    private static JsonObject tokenDiff(Parsed left, Parsed right) {
        var edits = new HistogramDiff().diff(COMPARATOR, new Tokens(left.tokens), new Tokens(right.tokens));
        JsonArray lhs = new JsonArray(), rhs = new JsonArray();
        for (Edit edit : edits) {
            for (int i = edit.getBeginA(); i < edit.getEndA(); i++) lhs.add(span(left.tokens.get(i)));
            for (int i = edit.getBeginB(); i < edit.getEndB(); i++) rhs.add(span(right.tokens.get(i)));
        }
        return result(lhs, rhs);
    }
    private static JsonObject span(Token token) {
        JsonObject span = new JsonObject();
        span.addProperty("start", token.start);
        span.addProperty("length", token.length);
        span.addProperty("content", token.text);
        return span;
    }
    private static JsonObject result(JsonArray left, JsonArray right) {
        JsonObject value = new JsonObject();
        value.add("left", left); value.add("right", right);
        value.addProperty("changed", !left.isEmpty() || !right.isEmpty());
        return value;
    }

    private static Tree tree(INode node, int offset) {
        if (node instanceof ILeafNode leaf && leaf.isHidden() && node.getText().isBlank()) return null;
        Tree value = new DefaultTree(TypeSet.type(type(node)), node instanceof ILeafNode ? node.getText() : "");
        value.setPos(node.getOffset() + offset); value.setLength(node.getLength());
        if (node instanceof ICompositeNode composite) {
            for (INode child : composite.getChildren()) {
                Tree next = tree(child, offset);
                if (next != null) value.addChild(next);
            }
            if (value.getChildren().isEmpty()) return null;
        }
        return value;
    }
    private static JsonObject treeDiff(Parsed left, Parsed right) {
        Tree a = tree(left.result.getRootNode(), left.offset);
        Tree b = tree(right.result.getRootNode(), right.offset);
        if (a == null) a = new DefaultTree(TypeSet.type("empty"));
        if (b == null) b = new DefaultTree(TypeSet.type("empty"));
        // Use the library's default matcher, without a registry or external generators.
        MappingStore mappings = new CompositeMatchers.SimpleGumtree().match(a, b);
        var actions = new SimplifiedChawatheScriptGenerator().computeActions(mappings);
        TreeContext ac = new TreeContext(), bc = new TreeContext();
        ac.setRoot(a); bc.setRoot(b);
        TreeClassifier classifier = new Diff(ac, bc, mappings, actions).createAllNodeClassifier();
        JsonArray lhs = marked(left, a, classifier.getDeletedSrcs(), classifier.getUpdatedSrcs(), classifier.getMovedSrcs());
        JsonArray rhs = marked(right, b, classifier.getInsertedDsts(), classifier.getUpdatedDsts(), classifier.getMovedDsts());
        JsonObject value = result(lhs, rhs);
        JsonArray script = new JsonArray();
        for (var action : actions) script.add(action.toString());
        value.add("actions", script);
        value.addProperty("movedSourceNodes", classifier.getMovedSrcs().size());
        return value;
    }
    @SafeVarargs private static JsonArray marked(Parsed parsed, Tree root, Set<Tree>... sets) {
        Set<Integer> positions = new HashSet<>();
        for (Tree leaf : root.preOrder()) {
            if (!leaf.isLeaf()) continue;
            for (Set<Tree> set : sets) if (set.contains(leaf)) positions.add(leaf.getPos());
        }
        JsonArray values = new JsonArray();
        for (Token token : parsed.tokens) if (positions.contains(token.start)) values.add(span(token));
        return values;
    }
    @Override public void stop() { }
}
