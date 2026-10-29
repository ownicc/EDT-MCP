# BSL method-aware JGit experiment

Throwaway experiment, 2026-10-29, tracked as `edt-iqn`. Only Difftastic and
JGit are candidates. The previous `bsl-jvm` GumTree experiment remains historical
and is not loaded by this probe. Production comparison/approval code is unchanged.

The purpose is to test how much BSL-specific behavior can be added around public
JGit APIs with the parser and libraries already installed in EDT. This is not a
JGit fork or a product implementation.

## Reproduce

Python >=3.11, the installed EDT/JDK and the preserved BSL-enabled Difftastic
binary are sufficient. No Maven, pip packages or new runtime JARs are downloaded.
The existing EDT Gson bundle serializes experiment evidence.

```sh
python3 experiments/bsl-jgit-methods/run.py \
  --edt /path/to/edt \
  --configuration /path/to/edt/configuration \
  --java-home /path/to/jdk \
  --difft /path/to/difft-bsl \
  --corpus /path/to/copied/KASL \
  --output /path/to/new/output
```

The output directory must be new. The runner compiles a headless Equinox
application against installed bundles and creates separate configurations and
empty workspaces for idle, parse-only, flat JGit, method-aware JGit and cleanup
profiles. It disables MCP and original bundle autostart; services needed by the
language provider can still start on demand. Each JVM has `-Xmx512m` and a
360-second whole-process timeout. The input project is never imported or saved.

Defaults: 10 warmup comparisons, then 50 recorded comparisons per selected
benchmark, plus the first comparison before warmup. All quality cases run once.
Every recorded sample is retained; median, nearest-rank p95 and maximum are
computed without trimming outliers. All profiles receive identical sources.
"Cold" means a new process/JVM, not an evicted filesystem cache. JVM uptime
before/after the first case and provider initialization time are retained.

## Adapter

- Parse detached snapshots through `IResourceServiceProvider` and `IParser`.
  EDT supplies the BSL lexer, CST, semantic model and source positions.
- Retain exact token spelling, comments and string contents. Ignore only hidden
  blank whitespace; preserve a separate exact `byteChanges` flag for every case.
- Extract EDT `Method` nodes with `NodeModelUtils.findActualNodeFor`. Match only
  unique method kind/name identities, with case-insensitive identity lookup.
  Compare tokens within each matched pair using JGit `HistogramDiff`; compare
  tokens outside method nodes separately. A rename is deletion/insertion, not
  an inferred match. Added/removed methods retain all their tokens.
- Diff the sequence of matched identities to record reordered methods separately
  from edits. A pure reorder has move metadata and no changed-token highlighting.
  A reorder plus an edit retains both. Order diff chooses one valid move
  interpretation; it does not establish author intent.
- Duplicate/overlapping/missing identities use flat token diff. Conditional or
  other non-region preprocessor directives conservatively use flat token diff
  because method ownership across branches is not established. The guard may
  also fall back for directive-looking lines inside a multiline string.
  Method placement relative to region boundaries is also checked, including a
  first-line BOM; crossing a boundary uses flat diff rather than hiding the move.
- Any parser syntax error uses exact text-line diff instead of trusting recovered
  syntax. Recovery/error messages are retained. Text fallback reports whitespace
  changes too. A structurally unchanged result never means proposed bytes can be
  omitted from an approval or write operation.
- Produce changed-line indexes and a basic monotonic line alignment with JGit
  `RawText`. This alignment is a presentation baseline, not synchronized
  method-move navigation. No SWT viewer or Apply/Reject lifecycle is implemented.

No arbitrary subtree matching, fuzzy rename matching, move-with-edit search,
matcher registry or extra grammar is introduced. Moves are reliable identity
reorders, not semantic equivalence claims.

## Measurements

Java per-comparison wall stages are parsing/token extraction, method extraction
and matching, token/text diff, range-to-line mapping plus line alignment, and the
whole in-memory result. Thread CPU time, process CPU time and allocated heap
bytes on the comparison thread are captured where the JDK supports them. The
process CPU counter is coarser and includes background EDT activity. Allocation
covers temporary allocations, not retained memory. Serialization of experiment
JSON, disk flushing and SWT rendering are outside Java comparison timing.

Snapshots around each case include heap used/committed/limit, non-heap use,
per-pool usage and lifetime peaks, cumulative GC count/time, threads, open files,
Linux RSS/high-water RSS and `/proc/self/io`. Resource-window deltas include the
first comparison, warmup, samples and experiment bookkeeping, not just one diff.
Unsupported or unobserved values are `null`, never invented zeroes. Pool/OS peaks
are lifetime maxima; they cannot be subtracted to obtain an operation's peak.

Native samples include snapshot files, launch, parsing/diff/JSON generation,
JSON reading, range/alignment validation and temporary-file cleanup. Native
parser/diff stages, heap allocation and GC are unavailable separately. Child
CPU, faults, block I/O, context switches and maximum RSS come from POSIX `wait4`;
post-exec RSS/threads/files/I/O are sampled through `/proc` at a nominal 0.5 ms
interval. Polling adds latency and may miss short-lived resource peaks.
`wait4` RSS covers the child launch lifetime, including a possible inherited
launcher high-water floor; use sampled post-exec RSS as additional evidence,
not as an exact peak. Native wrapper CPU is Python, not a measurement of the
future Java native-process adapter. The scopes are explicit in raw samples.

Native range checks cache source lines once. The historical fixture validator
split an entire source again for each aligned row; reusing that in a timed
large-input loop would measure quadratic harness work. The historical file is
preserved; this runner performs equivalent token checks with cached lines and
records invalid native alignment separately.

The idle profile warms the parser then observes it for one second. It is a
headless EDT baseline, not the user's visible EDT with projects and editors.
Whole-process RSS includes EDT services, JIT, input snapshots, retained result
DTOs and measurement code. It is not algorithm-only memory. Process-to-process
RSS differences are noisy; per-operation allocation/CPU and parser-only control
are more useful for estimating the adapter's cost.

The methods profile also performs 100 compute/discard cycles and takes resource
checkpoints every 10 cycles, without forced GC. A separate cleanup process runs
100 cycles, then creates 10 weakly referenced parse roots, requests GC outside
all latency samples and reports surviving roots and resources. This is evidence
about detached model lifetime, not proof of absence of leaks or a test of SWT
open/close behavior.

Cancellation checks cover a preexisting interrupt, an expired budget, a budget
expiring during a long token comparison and a successful subsequent comparison.
The comparator checks budgets for both token and line diffs. `IParser.parse`
itself has no cooperative cancellation argument: parser cancellation is checked
only at entry/exit and token extraction. No hard in-process deadline is claimed.
The runner separately times out a real native comparison, kills only its own
process group, waits for exit, checks for residual processes and verifies the
temporary directory is empty.

## Evidence and preservation

The recorded full run, its exact input snapshots, raw per-run JSON, logs and
standalone `preview.html` live under:

`/home/ownicc/Workstation/Kasl-diff-test/method-comparison-04/`

The committed [summary](summary.json) contains aggregate measurements and hashes
of the full raw summary, inputs and measured sources. Run 03 also passed; run 04
additionally records the actual OSGi-resolved library versions. The installation
contains two Gson versions, so selecting the last inventory row would be wrong.
Runs 01/02 and smoke outputs are retained as incomplete diagnostic evidence.

Copied Kasl source is not committed. The runner fingerprints its own sources,
fixtures, every copied module, the native plugin and the binary before and after
running. It retains versions, platform, JVM flags, input hash and probe JAR size.
The native source baseline is `130e39a5767fd67442a3a7cdf4b2f1f4dacfb01c`;
the old deployment, launcher, fixtures and previous raw outputs remain available.

The standalone preview compares the same snapshots using all three modes:
Difftastic, flat JGit and method-aware JGit. Move metadata is shown separately;
full text remains visible. Invalid native alignment cells are explicitly marked
and blanked only in the preview; the original JSON is retained unchanged.

## Results

Environment: Linux x86-64, EDT 2026.2.2.21, Axiom JDK 25.0.4.1, EDT BSL
28.1.0.v202610211527, Xtext 2.41.0.v20251124-0739, JGit
6.8.14.202605290850, resolved Gson 2.13.2. Execution on Windows/macOS is not
verified. The new probe JAR is 51,848 bytes; no additional runtime library was
installed or embedded.

**95 quality cases passed**, with all emitted spans checked against exact
source text and all Java line alignments checked for complete, ordered line
coverage. Nineteen selected cases have 50 recorded samples after 10 warmups.
The corpus includes 58 real modules with a whitespace change, three seeded
edits in copied Kasl modules, repeated calls, method reorder plus edit, duplicate
names, conditional compilation, region-boundary moves with/without BOM,
malformed input and generated modules of 10/100/1,000/5,000 procedures.

Method-aware JGit isolates precisely `100`/`120`, a single `1`/`2` among 300
repeated calls, and only `"A"`/`"C"` in the reorder-plus-edit case. Pure method
reordering produces move metadata without coloring whole methods as edited.
The nested-if fixture has 18 changed tokens, without the excessive 34-token
highlight from the historical GumTree adapter. Every numeric scaling case
checks that only its two changed numeric tokens are emitted by both JGit modes.
The copied real `ОбщийКлиент` edit highlights only `+` and `0` despite reflow.

EDT parses 49/58 corpus modules cleanly; nine use the explicit text fallback.
Difftastic parses 57/58 as BSL and uses text fallback for one. Status expectations
account for fallback showing whitespace changes. Neither parser's recovery
coverage is hidden behind a structural-unchanged result.

Warm wall-clock time in milliseconds, **median / p95 / maximum**:

| Input | Flat JGit, including parse/alignment | Method-aware JGit, including parse/alignment | Native launch/files/JSON/range validation |
|---|---:|---:|---:|
| Kasl `ОбщийКлиент`, 2.6 kB | 0.174 / 0.195 / 0.385 | 0.191 / 0.258 / 0.335 | 2.916 / 3.203 / 3.553 |
| 10 procedures, 0.9 kB | 0.071 / 0.076 / 0.329 | 0.094 / 0.131 / 0.648 | 2.857 / 3.077 / 3.198 |
| 100 procedures, 9.0 kB | 0.617 / 0.679 / 0.749 | 0.721 / 0.834 / 0.988 | 5.086 / 5.749 / 6.007 |
| 1,000 procedures, 91.8 kB | 5.778 / 9.956 / 10.551 | 6.863 / 10.513 / 11.754 | 30.876 / 32.333 / 41.631 |
| 5,000 procedures, 467.8 kB | 40.328 / 46.146 / 50.157 | 46.400 / 52.400 / 53.231 | 157.006 / 160.771 / 180.924 |

These are the implemented integration scopes, not timings of identical internal
operations. Native range handling is a Python harness proxy for a future Java
adapter. JVM measurements exclude experiment JSON serialization and SWT layout.
Sub-millisecond differences between profiles are noisy: e.g. parse-only can be
slower than parse-plus-diff in a separately warmed JVM. No statistical confidence
interval or universal algorithm speedup is claimed.

For 5,000 procedures the method-aware median stages are 35.990 ms parse/tokenize,
6.319 ms extract/match, 0.655 ms token diff and 1.187 ms line mapping/alignment.
Stage medians need not sum to the median end-to-end sample. Median comparison
thread CPU is 42.256 ms. New headless JVMs took roughly 5.4–7.1 seconds to reach
the provider; the first method-aware comparison took 28.3 ms. Product comparisons
would run in an already-running EDT, but first-use initialization still needs
an attended measurement there.

Median **allocated heap bytes per whole comparison**, in MiB:

| Input | Parser control | Flat JGit | Method-aware JGit |
|---|---:|---:|---:|
| Kasl `ОбщийКлиент` | 0.276 | 0.296 | 0.325 |
| 1,000 procedures | 13.911 | 15.066 | 17.945 |
| 5,000 procedures | 71.364 | 77.232 | 91.732 |

Method awareness adds about 2.9 MiB of allocations over flat JGit at 1,000
procedures and 14.5 MiB at 5,000. These are temporary allocations, not retained
heap or RSS. They are real work; "no new dependency" does not mean zero overhead.
Parsing dominates the large-input allocations and most of the elapsed time.

The idle headless process had median RSS about 911 MiB. Sampled whole-process
high-water RSS was about 1,100 MiB for the parser profile, 1,166 MiB for flat
JGit and 1,185 MiB for methods. The methods process additionally runs cancellation
checks and 100 large compute/discard cycles, so process totals do not represent
matched algorithm-only work. Do not subtract these peaks to estimate viewer
memory. Post-exec sampled native high-water medians were about 14.4 MiB for the
small Kasl edit, 31.8 MiB for 1,000 procedures and 83.3 MiB for 5,000, with the
sampling limits described above. Raw `wait4` peaks are preserved separately.

After 100 method-aware compute/discard cycles, open descriptors remained
125 → 125 and thread count did not grow (83 → 51 as background services settled).
In the separate cleanup diagnostic, all 10 weakly referenced roots were
collectible; heap used decreased from about 294.0 to 132.9 MiB after requested
GC. These bounded observations do not certify the production viewer's lifetime.
The native timeout exited with no residual PID/process group or temporary files;
the in-flight token budget was observed in about 1.02 ms. Mid-parse cancellation
remains unverified and is not guaranteed by this adapter.

All 60 preserved artifacts (58 copied modules, native plugin and executable)
still match their original hashes. Native JSON exposed invalid EOF alignment
rows in two seeded real-module edits; tracked separately as `edt-4jf`. The raw
answers remain unchanged and the preview marks them as invalid.

### More visible JGit highlighting

The JGit preview now uses its recorded line alignment and original line numbers.
Changed lines have a soft red/green background across the whole cell, a bold
colored gutter and a −/+ marker. Exact changed tokens use colored, slightly bold
text with no extra background. Spaces/tabs inside highlighted fragments have
CSS-generated ·/→ markers; original whitespace stays in the selectable text.
Reordered method lines are blue with ↕; only actual edits receive red/green
highlighting. Padding cells use a neutral hatch. UTF-16 ranges are mapped before
rendering, including supplementary Unicode characters and multiline changes.

Render existing evidence into a **new** HTML file without rerunning comparisons:

```sh
python3 experiments/bsl-jgit-methods/run.py \
  --preview-from /path/to/recorded/experiment-directory \
  --preview-output /path/to/new/preview.html
```

An existing output file is never overwritten. The recorded timing/resource
summary above measures commit `cfdd4d47`; this later presentation change does not
alter the Java comparison or those raw measurements. The previous preview and
native prototype remain available. The updated recorded-evidence preview is at
`/home/ownicc/Workstation/Kasl-diff-test/method-highlight-preview-02/preview.html`.
The previous, stronger inline-background variant remains in
`method-highlight-preview-01/` for comparison.

Rendering checks:

```sh
python3 -B -m unittest discover -s experiments/bsl-jgit-methods -p test_preview.py -v
```

The experiment supports extending JGit with bounded BSL method matching using
existing EDT dependencies. It does not support adding fuzzy AST matching or
claiming a finished replacement. Real SWT open/compute/dispose measurements,
visible-EDT baseline, first-use behavior, parser cancellation/input policy,
approval guards and other-OS verification are tracked as `edt-spa` before product
integration. The preserved Difftastic prototype remains the comparison baseline.
