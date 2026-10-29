package experiment;

import java.io.StringReader;
import java.lang.management.*;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

import com.google.gson.*;
import com._1c.g5.v8.dt.bsl.model.Method;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.equinox.app.*;
import org.eclipse.jgit.diff.*;
import org.eclipse.xtext.*;
import org.eclipse.xtext.nodemodel.*;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.*;
import org.eclipse.xtext.resource.IResourceServiceProvider;

/** Isolated experiment: detached parser snapshots, public JGit APIs, no workspace writes. */
public class MethodProbe implements IApplication {
    record Token(String type, String text, int start, int length) { }
    record Parsed(IParseResult tree, List<Token> tokens, int bom, List<String> errors) { }
    record Part(String key, int start, int end, List<Token> tokens, int regionBoundaryCount) { }
    record Projection(List<Part> methods, List<Token> outside, String fallback) { }
    record Pair(List<Token> left, List<Token> right) { }
    record Plan(List<Pair> pairs, List<Map<String, Object>> moves, String fallback) { }
    record Changes(List<Token> left, List<Token> right, List<Map<String, Object>> moves) { }
    record Lines(List<Integer> left, List<Integer> right, List<List<Integer>> aligned) { }
    record Run(Map<String, Object> value, Map<String, Object> sample) { }
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    static final ThreadMXBean THREAD = ManagementFactory.getThreadMXBean();
    static final com.sun.management.ThreadMXBean ALLOC = THREAD instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    IParser parser;

    @Override public Object start(IApplicationContext context) throws Exception {
        long entry = System.nanoTime();
        Platform.getBundle("com._1c.g5.v8.dt.bsl").start();
        parser = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(URI.createURI("memory:/probe.bsl")).get(IParser.class);
        if (THREAD.isThreadCpuTimeSupported()) THREAD.setThreadCpuTimeEnabled(true);
        if (ALLOC != null && ALLOC.isThreadAllocatedMemorySupported()) ALLOC.setThreadAllocatedMemoryEnabled(true);
        Path output = Path.of(System.getProperty("probe.output"));
        String mode = System.getProperty("probe.mode", "methods");
        JsonArray input = JsonParser.parseString(Files.readString(Path.of(System.getProperty("probe.input")))).getAsJsonArray();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", mode);
        report.put("providerSetupMs", (System.nanoTime() - entry) / 1e6);
        report.put("jvmUptimeAtProviderReadyMs", ManagementFactory.getRuntimeMXBean().getUptime());
        report.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("javaVersion", System.getProperty("java.runtime.version"));
        report.put("resolvedBundles", Map.of(
            "com._1c.g5.v8.dt.bsl", Platform.getBundle("com._1c.g5.v8.dt.bsl").getVersion().toString(),
            "org.eclipse.xtext", org.osgi.framework.FrameworkUtil.getBundle(IParser.class).getVersion().toString(),
            "org.eclipse.jgit", org.osgi.framework.FrameworkUtil.getBundle(HistogramDiff.class).getVersion().toString(),
            "com.google.gson", org.osgi.framework.FrameworkUtil.getBundle(Gson.class).getVersion().toString()));
        report.put("entry", snapshot());
        List<Object> cases = new ArrayList<>(); report.put("cases", cases);
        if (mode.equals("idle")) {
            JsonObject first = input.get(0).getAsJsonObject();
            for (int i = 0; i < 10; i++) {
                parse(first.get("before").getAsString()); parse(first.get("after").getAsString());
            }
            List<Object> idle = new ArrayList<>();
            for (int i = 0; i < 50; i++) { Thread.sleep(20); idle.add(snapshot()); }
            report.put("warmIdle", idle);
        } else if (mode.equals("cleanup")) {
            report.put("cleanupDiagnostic", cycles(input, true));
        } else {
            int warmup = Integer.getInteger("probe.warmup", 10), samples = Integer.getInteger("probe.samples", 50);
            report.put("warmupCount", warmup); report.put("samplesPerBenchmark", samples);
            // The first operation is deliberately before warmup; this is process-cold, not disk-cache-cold.
            for (var element : input) {
                JsonObject item = element.getAsJsonObject();
                String a = item.get("before").getAsString(), b = item.get("after").getAsString();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.get("id").getAsString()); row.put("resourcesBefore", snapshot());
                row.put("jvmUptimeBeforeFirstMs", ManagementFactory.getRuntimeMXBean().getUptime());
                Run first = compute(a, b, mode);
                row.put("jvmUptimeAfterFirstMs", ManagementFactory.getRuntimeMXBean().getUptime());
                row.put("result", first.value); row.put("firstSample", first.sample);
                if (item.has("benchmark") && item.get("benchmark").getAsBoolean()) {
                    for (int i = 0; i < warmup; i++) compute(a, b, mode);
                    List<Object> timings = new ArrayList<>();
                    for (int i = 0; i < samples; i++) timings.add(compute(a, b, mode).sample);
                    row.put("samples", timings);
                }
                row.put("resourcesAfter", snapshot()); cases.add(row);
                Files.writeString(output, JSON.toJson(report));
                System.out.println("METHOD_PROBE " + mode + " " + row.get("id"));
            }
            if (mode.equals("methods")) {
                report.put("cancellation", cancellationChecks(input));
                report.put("cycles", cycles(input, false));
            }
        }
        report.put("final", snapshot());
        Files.writeString(output, JSON.toJson(report));
        return EXIT_OK;
    }

    Run compute(String a, String b, String mode) {
        long cpu = threadCpu(), allocated = allocated(), process = processCpu(), start = System.nanoTime();
        Parsed left = parse(a), right = parse(b);
        long parsed = System.nanoTime();
        Plan plan = mode.equals("parse") ? new Plan(List.of(), List.of(), "parser-only")
            : mode.equals("flat") ? new Plan(List.of(new Pair(left.tokens, right.tokens)), List.of(), "flat")
            : plan(left, right, a, b);
        long matched = System.nanoTime();
        Changes changes;
        String fallback = plan.fallback;
        boolean malformed = !left.errors.isEmpty() || !right.errors.isEmpty();
        if (!mode.equals("parse") && malformed) {
            changes = textDiff(a, b); fallback = "syntax-errors:text-lines";
        } else {
            List<Token> lhs = new ArrayList<>(), rhs = new ArrayList<>();
            for (Pair pair : plan.pairs) diff(pair.left, pair.right, lhs, rhs);
            changes = new Changes(lhs, rhs, plan.moves);
        }
        long diffed = System.nanoTime();
        Lines lines = mode.equals("parse") ? new Lines(List.of(), List.of(), List.of()) : lines(a, b, changes);
        long aligned = System.nanoTime();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("left", changes.left); value.put("right", changes.right); value.put("moves", changes.moves);
        value.put("changed", !changes.left.isEmpty() || !changes.right.isEmpty() || !changes.moves.isEmpty());
        value.put("byteChanges", !a.equals(b)); value.put("fallback", fallback);
        value.put("leftErrors", left.errors); value.put("rightErrors", right.errors);
        value.put("leftTokens", left.tokens.size()); value.put("rightTokens", right.tokens.size());
        value.put("matchedPairs", plan.pairs.size()); value.put("lines", lines);
        value.put("detached", detached(left) && detached(right));
        long end = System.nanoTime(), cpuEnd = threadCpu(), allocatedEnd = allocated(), processEnd = processCpu();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("parseMs", (parsed - start) / 1e6); sample.put("matchMs", (matched - parsed) / 1e6);
        sample.put("diffMs", (diffed - matched) / 1e6); sample.put("lineAlignmentMs", (aligned - diffed) / 1e6);
        sample.put("endToEndMs", (end - start) / 1e6);
        sample.put("threadCpuNs", delta(cpu, cpuEnd)); sample.put("allocatedBytes", delta(allocated, allocatedEnd));
        sample.put("processCpuNs", delta(process, processEnd));
        return new Run(value, sample);
    }

    static boolean detached(Parsed p) {
        return p.tree.getRootASTElement() == null || p.tree.getRootASTElement().eResource() == null;
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

    static Projection project(Parsed p, String source) {
        // Conservative guard: do not infer method ownership through conditional compilation.
        // This may also reject directive-looking text in a string; flat fallback preserves it.
        List<Integer> regionBoundaries = new ArrayList<>(); int lineOffset = 0;
        for (String line : source.split("\n", -1)) {
            String t = (lineOffset == 0 && p.bom == 1 ? line.substring(1) : line).stripLeading().toLowerCase(Locale.ROOT);
            if (t.startsWith("#") && !(t.startsWith("#область") || t.startsWith("#конецобласти")
                    || t.startsWith("#region") || t.startsWith("#endregion")))
                return new Projection(List.of(), p.tokens, "preprocessor:flat-tokens");
            if (t.startsWith("#")) regionBoundaries.add(lineOffset);
            lineOffset += line.length() + 1;
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

    static Plan plan(Parsed left, Parsed right, String a, String b) {
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
    static final SequenceComparator<Tokens> COMPARATOR = new SequenceComparator<>() {
        @Override public boolean equals(Tokens a, int i, Tokens b, int j) {
            checkpoint(); Token x = a.list.get(i), y = b.list.get(j);
            return x.type.equals(y.type) && x.text.equals(y.text);
        }
        @Override public int hash(Tokens a, int i) { checkpoint(); Token t = a.list.get(i); return Objects.hash(t.type, t.text); }
    };
    static void diff(List<Token> a, List<Token> b, List<Token> left, List<Token> right) {
        checkpoint();
        for (Edit e : new HistogramDiff().diff(COMPARATOR, new Tokens(a), new Tokens(b))) {
            left.addAll(a.subList(e.getBeginA(), e.getEndA())); right.addAll(b.subList(e.getBeginB(), e.getEndB()));
        }
    }
    static List<Token> textLines(String source) {
        List<Token> tokens = new ArrayList<>(); int start = 0;
        for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n') {
            tokens.add(new Token("line", source.substring(start, i + 1), start, i + 1 - start)); start = i + 1;
        }
        if (start < source.length()) tokens.add(new Token("line", source.substring(start), start, source.length() - start));
        return tokens;
    }
    static Changes textDiff(String a, String b) {
        List<Token> l = new ArrayList<>(), r = new ArrayList<>(); diff(textLines(a), textLines(b), l, r);
        return new Changes(l, r, List.of());
    }
    static List<Integer> changedLines(String source, List<Token> changes) {
        List<Integer> starts = new ArrayList<>(); starts.add(0);
        for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n') starts.add(i + 1);
        Set<Integer> marked = new TreeSet<>();
        for (Token t : changes) {
            int first = Collections.binarySearch(starts, t.start), last = Collections.binarySearch(starts, t.start + Math.max(0, t.length - 1));
            if (first < 0) first = -first - 2; if (last < 0) last = -last - 2;
            for (int i = first; i <= last; i++) marked.add(i);
        }
        return new ArrayList<>(marked);
    }
    static Lines lines(String a, String b, Changes c) {
        checkpoint();
        RawText left = new RawText(a.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RawText right = new RawText(b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<List<Integer>> aligned = new ArrayList<>(); int x = 0, y = 0;
        RawTextComparator comparator = new RawTextComparator() {
            @Override public boolean equals(RawText x, int i, RawText y, int j) { checkpoint(); return DEFAULT.equals(x, i, y, j); }
            @Override protected int hashRegion(byte[] raw, int ptr, int end) {
                checkpoint(); int hash = 5381;
                while (ptr < end) hash = (hash << 5) + hash + (raw[ptr++] & 0xff);
                return hash;
            }
        };
        for (Edit edit : new HistogramDiff().diff(comparator, left, right)) {
            checkpoint();
            while (x < edit.getBeginA() && y < edit.getBeginB()) aligned.add(Arrays.asList(x++, y++));
            while (x < edit.getEndA() || y < edit.getEndB()) aligned.add(Arrays.asList(
                x < edit.getEndA() ? x++ : null, y < edit.getEndB() ? y++ : null));
        }
        while (x < left.size() || y < right.size()) aligned.add(Arrays.asList(x < left.size() ? x++ : null, y < right.size() ? y++ : null));
        return new Lines(changedLines(a, c.left), changedLines(b, c.right), aligned);
    }

    static void checkpoint() {
        Long end = DEADLINE.get();
        if (Thread.currentThread().isInterrupted() || (end != null && System.nanoTime() >= end)) throw new CancellationException("comparison budget");
    }
    Map<String, Object> cancellationChecks(JsonArray input) {
        JsonObject item = input.get(0).getAsJsonObject();
        Parsed a = parse(item.get("before").getAsString()), b = parse(item.get("after").getAsString());
        Map<String, Object> result = new LinkedHashMap<>();
        for (String kind : List.of("interrupt", "expired-budget")) {
            boolean observed = false;
            if (kind.equals("interrupt")) Thread.currentThread().interrupt(); else DEADLINE.set(System.nanoTime() - 1);
            try { diff(a.tokens, b.tokens, new ArrayList<>(), new ArrayList<>()); }
            catch (CancellationException expected) { observed = true; }
            finally { Thread.interrupted(); DEADLINE.remove(); }
            if (!observed) throw new AssertionError(kind);
            result.put(kind, observed);
        }
        List<Token> many = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) many.addAll(a.tokens);
        long started = System.nanoTime(); boolean inFlight = false;
        DEADLINE.set(started + 1_000_000);
        try { diff(many, many, new ArrayList<>(), new ArrayList<>()); }
        catch (CancellationException expected) { inFlight = true; }
        finally { DEADLINE.remove(); }
        if (!inFlight) throw new AssertionError("in-flight budget");
        result.put("inFlightBudgetObserved", true); result.put("inFlightBudgetElapsedMs", (System.nanoTime() - started) / 1e6);
        compute(item.get("before").getAsString(), item.get("after").getAsString(), "methods");
        result.put("subsequentComparisonSucceeded", true);
        result.put("parserMidCallCancellation", "unavailable: IParser.parse(Reader) has no cooperative cancellation argument");
        return result;
    }
    Map<String, Object> cycles(JsonArray input, boolean gcDiagnostic) throws Exception {
        // Compute/discard model lifecycle only: no SWT viewer is constructed in this spike.
        JsonObject item = input.get(input.size() - 1).getAsJsonObject();
        List<Object> checkpoints = new ArrayList<>();
        List<WeakReference<EObject>> roots = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>(); result.put("before", snapshot());
        for (int i = 0; i < 100; i++) {
            compute(item.get("before").getAsString(), item.get("after").getAsString(), "methods");
            if (i % 10 == 9) checkpoints.add(snapshot());
        }
        if (gcDiagnostic) {
            for (int i = 0; i < 10; i++) roots.add(new WeakReference<>(parse(item.get("before").getAsString()).tree.getRootASTElement()));
            result.put("beforeRequestedGc", snapshot());
            System.gc(); Thread.sleep(200); // Separate diagnostic process; never called in latency runs.
            result.put("afterRequestedGc", snapshot());
            result.put("weakRootsRemaining", roots.stream().filter(r -> r.get() != null).count());
        }
        result.put("checkpointsEvery10Cycles", checkpoints); result.put("count", 100); result.put("after", snapshot());
        return result;
    }

    static long threadCpu() { return THREAD.isCurrentThreadCpuTimeSupported() ? THREAD.getCurrentThreadCpuTime() : -1; }
    static long allocated() { return ALLOC != null && ALLOC.isThreadAllocatedMemorySupported() ? ALLOC.getThreadAllocatedBytes(Thread.currentThread().getId()) : -1; }
    static long processCpu() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean bean ? bean.getProcessCpuTime() : -1;
    }
    static Long delta(long before, long after) { return before < 0 || after < 0 ? null : after - before; }
    static Map<String, Object> usage(MemoryUsage u) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("used", u.getUsed()); result.put("committed", u.getCommitted()); result.put("limit", u.getMax() < 0 ? null : u.getMax()); return result;
    }
    static Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        result.put("heap", usage(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()));
        result.put("nonHeap", usage(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage()));
        result.put("processCpuNs", processCpu() < 0 ? null : processCpu());
        result.put("threads", THREAD.getThreadCount()); result.put("peakThreads", THREAD.getPeakThreadCount());
        var os = ManagementFactory.getOperatingSystemMXBean();
        result.put("openFiles", os instanceof com.sun.management.UnixOperatingSystemMXBean bean ? bean.getOpenFileDescriptorCount() : null);
        Map<String, Object> gc = new LinkedHashMap<>(), pools = new LinkedHashMap<>();
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) gc.put(bean.getName(), Arrays.asList(
            bean.getCollectionCount() < 0 ? null : bean.getCollectionCount(), bean.getCollectionTime() < 0 ? null : bean.getCollectionTime()));
        for (var bean : ManagementFactory.getMemoryPoolMXBeans()) pools.put(bean.getName(), Map.of("usage", usage(bean.getUsage()), "peak", usage(bean.getPeakUsage())));
        result.put("gcCountTimeMs", gc); result.put("pools", pools);
        Map<String, Long> status = new LinkedHashMap<>(), io = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status")))
                if (line.startsWith("VmRSS:") || line.startsWith("VmHWM:")) status.put(line.split(":")[0], Long.parseLong(line.strip().split("\\s+")[1]) * 1024);
            for (String line : Files.readAllLines(Path.of("/proc/self/io"))) {
                String[] pair = line.split(":"); io.put(pair[0], Long.parseLong(pair[1].strip()));
            }
        } catch (java.io.IOException unsupported) { /* null below means unavailable, never zero */ }
        result.put("linuxRssBytes", status.isEmpty() ? null : status); result.put("linuxIo", io.isEmpty() ? null : io);
        return result;
    }
    @Override public void stop() { }
}
