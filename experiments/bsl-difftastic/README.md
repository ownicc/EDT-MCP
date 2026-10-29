# BSL structural diff experiment

Date: 2026-10-29. Status: throwaway feasibility experiment, not an EDT integration.

The experiment confirms that difftastic can compare BSL syntax using the existing
tree-sitter-bsl grammar. A standalone [preview](preview.html) separates syntax
highlighting from changed-token highlighting. The [recorded results](results.json)
include timings and fallback reasons. No installed EDT files or product code were
changed. The previously approved native Compare design has not been replaced.

## Inputs and reproduction

- [Difftastic 0.71.0](https://github.com/Wilfred/difftastic/tree/0.71.0),
  commit `b7d119e90ac9f972f03f69508da765dacd302c0c`.
- [tree-sitter-bsl v0.1.7](https://github.com/alkoleft/tree-sitter-bsl/tree/v0.1.7),
  commit `b424b53e72ca0746ea9a7131d80aec1947523a0c`.
- Rust 1.85.0, release build, four build jobs, Linux x86-64.
- Unmodified system difftastic 0.71.0 is the plain-text baseline for `.bsl` files.

From the repository root, with Git, Python >=3.10, rustup/C build prerequisites,
and an unmodified `difft` on PATH:

```sh
bash experiments/bsl-difftastic/reproduce.sh
```

This downloads the pinned sources into a new `/tmp/edt-bsl-difftastic-*` directory,
verifies commit IDs, applies [the patch](difftastic-bsl.patch), builds with the
patched Cargo.lock, and runs [the experiment](run.py). Cargo may download the
pinned Rust toolchain and dependencies. It prints the binary and preview paths.
The script does not publish anything or modify the installed EDT.

The output contains every before/after fixture, both engines' raw JSON results,
tree-sitter dumps, colored terminal diffs, a summary, and a standalone HTML file.
Timings are three sequential process launches per input, reported as the median;
they include process startup, parsing and JSON serialization. This is a small
warm-filesystem measurement, not a latency guarantee or a broad parser benchmark.
Raw JSON hashes identify that run's files, including their temporary paths.

## Changes to difftastic

The small patch adds a local grammar dependency, a `BSL` language with `.bsl` and
`.osl` detection, grammar-provided highlight queries, and parentheses/bracket
delimiters. Entire BSL string/date nodes are atoms so meaningful whitespace in
literals cannot disappear during tree conversion. Embedded query strings remain
strings; the separate SDBL parser is not enabled.

One additional guard changes `node.is_error()` to
`node.is_error() || node.is_missing()` during syntax conversion. Before this
change, `Если Тогда` without its expression/end keyword stayed in structural mode:
tree-sitter inserted zero-width missing nodes which difftastic did not count.
After the guard, that fixture and incomplete-string/parenthesis fixtures fall
back to text with explicit parse-error reasons. This is an experimental change
to upstream error accounting, not just language registration; it needs broader
review before shipping.

## Observations

All 22 scenario assertions passed after a fresh checkout, patch application and
build. The checks validate status, structural versus fallback mode, line ranges,
and every changed span against UTF-8 source bytes.

The upstream binary's unit suite also passed: 123 passed, zero failed, one
upstream-ignored test. This includes the five `parse::tree_sitter_parser` tests
covering parser configuration and node-name validation across languages. Run the
suite inside the patched difftastic checkout with:

```sh
cargo +1.85.0 test --release --locked --jobs 4 --bin difft
```

| Input | Observed result |
| --- | --- |
| Reflow one call across multiple lines | `BSL`, no syntax changes; baseline text mode reports changes |
| Reflow plus `100` → `120` | Exactly two changed spans: old `100`, new `120`; baseline has 50 spans |
| Nested conditions, regions, preprocessor, compilation/extension annotations | Structural comparison with BSL recognition |
| English BSL with async/await | Structural comparison |
| Comments, escaped quotes, Cyrillic and emoji | Changes retained; byte ranges validated |
| Whitespace inside ordinary and multiline query strings | Remains a change |
| Reorder two procedures | Changes shown; no distinct move operation or method identity |
| Incomplete condition, string, parenthesis | Text fallback with explicit parse errors after the guard |
| New/deleted module | `created` / `deleted`; JSON omits changed-token arrays |
| Only LF/CRLF or BOM changes | No syntax changes although bytes differ |
| Change only keyword capitalization | Reported as a change |
| Artificial graph/byte limit of 1 | Text fallback with an explicit limit reason |

The repository corpus contains 15 BSL fixtures. Fourteen remained in BSL mode;
`TestConfiguration/src/CommonModules/Error/Module.bsl` contains the literal
`Error` and fell back to text. A whitespace-only comparison forces actual parsing
for this check; comparing identical bytes alone would not test the parser.

Small examples took approximately 2 ms each on this machine. A generated module
with 1,000 procedures, 4,999 lines and 118,779 UTF-8 bytes took approximately
40 ms median for one numeric change and returned exactly two changed spans.
A separate run of that comparison peaked at 33,340 KiB RSS. The full multi-language
Linux binary was 119,579,856 bytes before stripping, 118,163,480 after stripping,
and 11,899,159 gzip-compressed. Packaging only the needed languages was not tried.
Only Linux x86-64 was built; Windows/macOS packaging remains unverified.

## Implications for the EDT window

- JSON supplies line correspondence and changed spans, not a full syntax-color
  stream. The preview obtains lexical styles from tree-sitter's parse dump and
  change backgrounds from JSON. A product viewer should use a supported token
  API or EDT's BSL highlighting rather than parse a debugging dump.
- Line numbers are zero-based and columns are UTF-8 byte offsets. SWT/JavaScript
  positions need UTF-16 conversion, especially for Cyrillic and emoji.
- `chunks` are change groups, not a complete ordered row list. Use
  `aligned_lines` for layout. Alignment is omitted when there are no syntax
  changes; creation/deletion also require rendering from the stored source.
- JSON is an upstream unstable feature requiring `DFT_UNSTABLE=yes`.
  Pin the engine/schema and retain a text fallback. Never parse terminal ANSI
  output as the GUI data contract.
- Syntax equality is not byte equality. Apply/Reject must act on exact prepared
  bytes, including BOM and line endings, independently of structural status.
- A custom viewer is still required. This experiment does not establish a public
  API for feeding arbitrary structural ranges into Eclipse's stock TextMergeViewer,
  which performs its own comparison.
- The HTML has no Apply/Reject actions or EDT lifecycle. For no-change inputs its
  two snapshots are displayed in row order because JSON provides no alignment.
  It demonstrates real highlighting/data, not a final window layout.

Recommendation: retain difftastic + tree-sitter-bsl as a viable candidate and use
a custom read-only viewer to combine BSL highlighting with its structural ranges.
Next decide native SWT source views versus an embedded HTML viewer, then amend
the existing design. Move recognition and structural parsing inside query strings
can remain separate improvements. Larger real-world corpus coverage, process
cancellation, platform packaging, and comprehensive malformed-input validation
are required before production integration.

## Native EDT prototype

The review tab now has an optional native SWT structural viewer. Supply the
absolute path of the BSL-enabled executable built above in the EDT JVM option
`-Dedt.mcp.difftastic=/absolute/path/to/difft`. With no configured executable,
the standard native compare uses the installed BSL viewer. A failed or timed-out
engine falls back to that viewer with a visible notice.

The structural surface uses EDT's BSL lexer and syntax-color preferences,
read-only snapshots, original line numbers, aligned scrolling, changed-token
backgrounds and previous/next change actions. Apply/Reject and prepared-byte
commit validation remain in the existing review lifecycle. JSON positions are
validated against the snapshots and converted from UTF-8 to UTF-16. The adapter
handles omitted chunks, final newline rows and virtual end-of-file markers.

The toolbar has a Light/Dark selector, with Dark selected for a new review.
Only the source panes change theme; the GTK shell can stay on Adwaita light.
Light restores EDT's configured syntax attributes. Dark uses a separate palette
for the same BSL token categories and distinct added/removed backgrounds.
Changing theme preserves the text, selection and scrolling position.
Dark changed tokens use stronger green/red backgrounds and white text for
readability. Original line numbers follow structural spans: removed on the left
are red, added on the right are green, in either theme. Alignment padding has
no number, and formatting-only lines keep their normal number color.

To open the attended Kasl example in a disposable imported copy:

```sh
python experiments/bsl-difftastic/kasl_review.py --directory /path/to/copied/KASL
```

The script requests an expression reflow with `+ 0`, verifies that disk bytes
remain unchanged, and prints the review ID. It never accepts the proposal.
Close or reject the pending review before requesting a different change for
the same module. The first prototype uses an explicitly configured Linux
binary; automatic installation and other-platform packages are not included.
