# Exact BSL section text diff verification

Beads: `edt-mmh.2`. User approved exact section-scoped text comparison.

## Result

`get_comparison_node` retains its existing parameters and brief default output.
With `textDiff`, a module request compares the whole module; a section request
compares its native paired source fragments. Regions and outside-method sections
exclude intervening child methods/regions. Methods retain native documentation,
signature and body ranges. Separate signature/body children are used when the
method itself supplies no ranges; a full parent range is never duplicated.

Each fragment carries its original UTF-16 offset/length and 1-based source
line/column for each side. Unified hunk line numbers remain module-relative.
Partial lines remain partial, with explicit fragment-boundary newline notices.
All fragments share one output budget. Missing/invalid/overlapping ranges return
unavailable rather than equality or an inferred deletion. Absent sections and
empty existing text remain distinct. EDT structural/merge status is preserved,
so equal region-own text can coexist with a changed descendant in the summary.

Snapshots are captured inside the existing comparison read boundary. JGit formats
detached text outside it. No dependency, parser, UI control or write behavior was
added. The tool guide and generated page describe the new scope; the page was
regenerated using the production `GuideRenderer` in the test runtime.

## Verification

- Initial focused baseline: 99 tests passed.
- Five regressions failed before implementation: region-own scope, region-header
  coordinates, split method capture, disjoint source coordinates and invalid ranges.
- A later literal-newline-marker test reproduced an accidental change to source
  text in the formatter. Marker replacement now matches only standalone JGit
  notices and preserves the byte budget.
- Independent review reproduced a shared line-limit defect through the public
  fragment snapshot interface: concatenating a 20000-line unterminated fragment
  with a separate one-line fragment counted only 20000 lines. A regression failed
  before the fix. Input limits now sum each fragment's line count and UTF-16 length;
  19999 + 1 lines pass and 20000 + 1 are refused. Follow-up review confirmed the fix
  and found no other actionable issue.
- Final focused verification: **116 tests, no failures/errors/skips**, including
  tool integration, source-side reads, section capture, formatter and guide ratchet.
- Final full `mvn -o -f mcp/pom.xml clean verify --batch-mode -T 1` under isolated
  Xvfb: **BUILD SUCCESS**, 754 test classes, **13869 tests, 0 failures, 0 errors,
  30 skips** (10:35). Surefire XML totals independently match. Skips cover opt-in
  workbench/UI checks, Windows-only paths and a runtime type-provider condition.
- Documentation generator tests: **5 passed**. Agent-pack validator passed for
  13 skills, 111 registered/documented tools and 1 proxy tool. `git diff --check`
  passed.

Final logs: `/tmp/edt-section-final-focused.log`,
`/tmp/edt-section-final-full.log`, `/tmp/edt-section-docs-tests.log`.
Expected failing regressions: `/tmp/edt-section-red.log`,
`/tmp/edt-section-boundary-red.log`, `/tmp/edt-section-line-budget-red.log`.
The first full run was intentionally stopped after the review finding; the final
full log above verifies the corrected source.

## Native EDT evidence and scope

A disposable characterization probe used the installed EDT 2026.2 parser and
section descriptors. It confirmed two own fragments for a region and a full
method range including its preceding comment. Integration mode additionally
provided signature (header/end keyword) and body children overlapping the full
parent; capture retained the full method once. Native captures were checked
against expected region/method text. Source and log are preserved locally at
`/tmp/edt-section-native-probe.java` and `/tmp/edt-section-native-final.log`.
Descriptor reflection was confined to that disposable probe; production reads
public comparison regions. The probe was removed before the final clean build.

Verification covers native descriptor shape, session-side readers, snapshot
capture and formatting. Live acceptance in the user's installed EDT, Windows
rendering and release/platform certification remain separate work. This change
was not installed in the user's EDT and no release was created.
