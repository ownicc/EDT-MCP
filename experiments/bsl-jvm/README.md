# BSL comparison inside the EDT JVM

Date: 2026-10-29. **Throwaway experiment; the installed native Difftastic viewer
is preserved. No production comparison or approval code was changed.**

The experiment compares the same source snapshots with:

1. The existing BSL-enabled Difftastic 0.71.0 binary.
2. EDT's Xtext parser and the existing JGit HistogramDiff over token sequences.
3. EDT's Xtext syntax-node tree (CST) and GumTree core 4.1.0's SimpleGumtree
   matcher plus SimplifiedChawathe actions. This is an initial CST adapter;
   a projection of the semantic EMF model was not implemented.

## Reproduce

Requirements for this experiment's harness: an installed EDT with absolute
bundle URLs in its `bundles.info`, JDK, Maven, Python >=3.11 and the existing
BSL-enabled Difftastic executable. Maven downloads the pinned GumTree runtime
dependencies from Maven Central. These are development/reproduction tools;
the Java comparison methods themselves do not invoke Python, Maven or Difftastic.

```sh
python3 experiments/bsl-jvm/run.py \
  --edt /path/to/edt \
  --configuration /path/to/edt/configuration \
  --java-home /path/to/jdk \
  --maven /path/to/maven/bin/mvn \
  --difft /path/to/difft-bsl \
  --corpus /path/to/copied/KASL \
  --output /path/to/new/experiment-directory \
  --profile
```

The output directory must be new. The harness compiles a small headless Equinox
application and writes its own configuration and empty workspace there. It uses
`IResourceServiceProvider` / `IParser.parse(Reader)` directly: source projects
are never imported, linked, saved or opened in the running EDT. All original
bundles have autostart disabled in this separate configuration; the language
provider can still start EDT services on demand. MCP autostart is disabled.
Each Java process is bounded to 240 seconds and a 512 MiB heap; on POSIX timeout
the harness terminates its own process group. This is a whole-run bound, not
cooperative cancellation inside each algorithm.

Outputs include exact `input.json` snapshots, Java spans/actions/errors,
per-case raw `difftastic.json`, `results.json`, logs and a standalone
`preview.html` with all three engines' before/after highlights. The preview
does not implement synchronized row alignment, syntax colors or Apply/Reject.
`--profile` additionally runs parsing alone, parsing + JGit, and parsing +
GumTree in separate JVMs on the same inputs.

## Preserve the previous prototype

The native viewer source baseline is commit
`130e39a5767fd67442a3a7cdf4b2f1f4dacfb01c`. The existing experiment at
`experiments/bsl-difftastic/` and its fixtures remain unchanged.

The tested native plugin is `1.0.0.202610291800`, SHA256
`2021a4f633b9043060b210ec6f2d5af29cc312c48c3623216339797e4f65ca56`.
The BSL-enabled executable's SHA256 is
`eb4f6bdd9cff24d3b3074ba4652b21e46cc908a7fff755ead7d08852563e1f3f`.
Their paths and the 58 copied modules' hashes are captured before a run and
verified afterwards. The existing installation/configuration/workspace and
its launcher remain available for attended comparison with the native viewer.

The recorded run is under
`/home/ownicc/Workstation/Kasl-diff-test/jvm-comparison-03/`.
Only the compact [summary](summary.json) is committed; copied Kasl source and
the full raw outputs stay outside this repository.

## Results

Environment: EDT 2026.2.2.21, Axiom JDK 25.0.4.1, Linux x86-64,
Xtext 2.41.0, EDT BSL bundle 28.1.0 and JGit 6.8.14. The Java APIs/algorithms
are platform independent; execution on Windows/macOS remains unverified.

- **83 cases completed with no Java-engine exceptions.** Each Java engine
  matched every expected coarse changed/unchanged status. The harness validates
  emitted UTF-16 ranges against exact source text, and Difftastic UTF-8 ranges
  through the existing experiment validator.
- The 22 original fixtures plus two extra cases cover reflow, meaningful spaces
  in strings/query strings, comments, Unicode, malformed syntax, repeated calls
  and a procedure move with an edit. All 58 Kasl modules are separately compared
  with a leading blank line: this corpus probe tests parser coverage and one
  formatting transformation, not broad real-edit quality.
- All parsed roots were detached (`eResource() == null`). **49/58 Kasl modules
  had no EDT parse errors.** Nine used recovered parse trees and require a
  fallback policy before product use. All three intentionally malformed
  fixtures also report errors. Results from recovered trees are diagnostic;
  this experiment does not implement the production fallback.
- Both Java methods isolate exactly `100` / `120` despite reflow, `1` / `2`
  among 30 repeated calls, and `500` / `1500` in a generated 1,000-procedure
  module. These precise changed-content expectations are asserted.
- The naive GumTree CST adapter produces **34 changed token spans** for the
  nested-if fixture, versus **18** for both JGit and Difftastic. It classifies
  the original call moved into the new conditional as a move and colors it.
  This needs a presentation policy or a better tree projection; coarse status
  agreement alone would hide the difference.
- GumTree detects a whole procedure move. Flat JGit token matching can pair
  repeated procedure-boundary keywords across methods; its highlighted ranges
  can cross method boundaries. A method-aware projection would need separate
  evaluation before replacing structural matching.

### Dependency cost

JGit and the BSL parser already come from EDT; the token approach adds no
third-party runtime libraries to the existing plugin. GumTree's unmodified
Maven runtime closure is **11 JARs / 29,462,782 bytes** (28.1 MiB), including
fastutil (24.0 MB), Guava 19.0, Gson 2.14.0, simmetrics, JGraphT, jheaps,
apfloat, classindex, commons-codec and error-prone annotations. Some overlap
with EDT dependencies, but reuse/pruning and OSGi version compatibility have
not been established. The closure is deliberately embedded only in the probe.

No tree-sitter bindings, native parser or standalone BSL parser were added.

### Timing and memory limits

For the 1,000-procedure numeric edit, parsing both sources took 7.852 ms once;
median diff-only time across three repetitions was 0.217 ms for JGit and
48.746 ms for GumTree. Difftastic's median whole-process time was 28.839 ms.
Java timings exclude parsing and EDT startup; native timings include process
startup. These are exploratory, warm-process measurements, not a comparable
end-to-end performance claim. Input-size scaling and adversarial limits were
not benchmarked.

The separate 512 MiB-heap JVM profiles had sampled process RSS high-water marks
of approximately **851 MiB parsing alone, 798 MiB with JGit, 930 MiB with
GumTree**. Each includes the headless EDT runtime, services, JIT and retained
experiment data. RSS sampling/noise prevents interpreting differences as
precise incremental algorithm costs. Heap used after a requested final GC was
approximately 120 / 120 / 124 MiB respectively. These measurements establish
neither a low-memory guarantee nor the extra memory of opening a viewer in an
already-running EDT. The first combined 1 GiB-heap experiment reached about
1.35 GiB RSS, which motivated the separate, bounded profiles.

## Decision supported by this spike

EDT can parse arbitrary before/after snapshots inside its existing JVM without
project-model mutation or a second grammar. EDT + JGit is the leanest candidate
for further evaluation, with known method-boundary limitations. GumTree's
default CST integration has measurable dependency and presentation costs and
does not yet justify a production dependency.

The current evidence supports a JVM direction, but does not select a finished
replacement. A product design still needs changed-line alignment, method
boundaries, parser-recovery behavior, cancellation/resource limits and attended
validation. The original native prototype is retained for every such check.
