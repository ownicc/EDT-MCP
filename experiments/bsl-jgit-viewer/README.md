# Method-aware JGit in the EDT review viewer

The opt-in JVM flag `-Dedt.mcp.bslDiff=jgit` uses detached EDT parser snapshots and public JGit APIs inside the existing native SWT review editor. It needs no extra runtime JAR or executable. The standard compare remains the default; `edt.mcp.difftastic` and the preserved native installation remain available independently. When both flags are present, the explicit JGit opt-in takes precedence.

Unique method kind/name identities separate method reordering from edits. Ambiguous identities, conditional preprocessing and region-boundary changes fall back to flat tokens. Parser errors fall back to exact text lines. These fallbacks remain visible. Formatting-only comparisons still indicate exact text changes, and the review applies the prepared bytes through the existing guards.

Each input is limited to 524288 UTF-16 units and 20000 lines. Larger inputs use the standard viewer with a limit notice. JGit jobs are serialized, cancel when their controls close, and check cancellation and a two-second compute budget at parser boundaries and during token/line comparison. The parser API cannot interrupt the middle of a parse; the budget is not a hard parser deadline.

The light theme follows the accepted preview03: soft changed-line backgrounds, pastel exact fragments with bold colored text and underline, original numbered +/- gutters, blue method moves and hatched padding. Whitespace markers are paint overlays over real selectable characters. The dark theme remains selectable. Whitespace drawing is limited to visible columns, including horizontal scrolling.

## Isolated validation

The runner copies the preserved installation and workspace into a new output directory and adds a test-only startup fragment. It uses **Xvfb by default**; repeated editor tests never open windows on the user's desktop. It never clicks Apply, and its fixture commit callback fails if invoked. Every ordinary close must transition the fixture review to rejected.

```sh
python3 -B experiments/bsl-jgit-viewer/run.py \
  --native-install /path/to/preserved/edt \
  --native-workspace /path/to/preserved/workspace \
  --evidence /path/to/method-comparison-04 \
  --java-home /path/to/jdk25 \
  --bundle mcp/bundles/com.ditrix.edt.mcp.server/target/com.ditrix.edt.mcp.server-1.0.0-SNAPSHOT.jar \
  --output /path/to/new/viewer-evidence
```

The supplied bundle must come from a successful build of the current sources. Python uses only its standard library; the test runner additionally needs `xvfb-run` and Xvfb. These are test infrastructure, not product dependencies. The output contains `launch.json`, a new EDT/workspace, PNG captures of actual SWT controls, frozen inputs/expected ranges, process logs and `viewer-results.json`. The launch file records the isolated process-group leader for cleanup; verify its live command before terminating that group. The runner leaves the isolated process running for optional MCP inspection, never accepts a real review, and never overwrites an evidence directory.

The probe compares all 95 frozen experiment cases with their original token/move ranges and checks full monotonic source-line coverage. It exercises the actual `BslWriteReviewInput`, selection, both themes, an oversized-input notice, a 250000-space changed comment, 100 open/compute/close cycles and 20 close-during-compute cycles. Resource snapshots include warm visible-workbench and warm-viewer baselines, heap/GC/RSS, file descriptors, threads and tracked SWT resources. Weak roots are diagnosed outside latency timing; empty jobs reuse idle workers before GC to reduce scheduler noise. Custom editor disposal invokes the base CompareEditorInput cleanup after its last child, and the creation signal holds a weak control reference.

SWT tracking starts after the workbench exists, so it does not enumerate preexisting resources. Whole-process RSS/FD changes include unrelated EDT service and JAR initialization; lifetime RSS peaks are never subtracted as incremental viewer cost. This bounded run does not establish absence of all possible leaks.

Linux/EDT 2026.2 validation is separate from product promotion. Windows/macOS, older EDT versions, real attended scrolling/selection, and full product e2e with its dedicated TestConfiguration fixtures still need their own environments. No release certification is implied.

## Aligned SWT overview

The structural viewer now uses one shared scrollbar containing both a draggable viewport and red/green change marks. Blue marks show the two endpoints of a method move. Alignment padding is retained; both panes always share the same display-row position. There is no compact flag or separate minimap lane.

Drag the centre of the thumb to scroll, click an edge mark to select a hunk, or click elsewhere on the track to jump. The bar accepts wheel, arrow, Page Up/Down and Home/End keys and exposes its value as an accessible scrollbar. The text panes retain native selection, horizontal scrolling and keyboard navigation. M1/M2 badges in a dedicated gutter jump to the other endpoint without offsetting the two panes. Hovering a badge gives the peer source line; short method headers also show that line after their declaration. The badge stays available for long signatures and horizontal scrolling. Previous/next visits adjacent changed blocks and the move endpoints; formatting-only changes do not become structural marks.

The control is a small SWT Canvas, rather than platform-specific painting over a native operating-system scrollbar. All parser rules, fallback notices, prepared output bytes and approval guards are unchanged. The standard compare remains the default; opt in with `-Dedt.mcp.bslDiff=jgit` as above.

Add `--overview` to the runner for a focused check of the 95 frozen cases, actual review lifecycle, exact aligned source coverage, shared scrolling, mark clicks, thumb dragging, keyboard, move endpoint navigation, themes, resizing, empty sides, whitespace, long lines and disposal during computation. `OverviewProbe` is a test-only fragment, never part of the product bundle. Its optional `-Dviewer.probe.demo=true` mode opens a detached fixture comparison for manual inspection.

## Aligned overview evidence (2026-10-31)

[overview-summary.json](overview-summary.json) pins the final Java/SWT source and evidence. The canonical build passed 12025 tests (five skips), final focused checks passed 12 tests, and `overview-viewer-03` passed the 95 frozen cases plus the new native interactions. Both review findings have regressions: a whole tall mark is clickable and a long declaration retains its gutter badge. See [the report](../../docs/superpowers/reports/2026-10-31-bsl-diff-overview.md) for commands, scope and limitations.

The verified local demonstration launcher is `/home/ownicc/Workstation/Kasl-diff-test/open-edt-overview.sh`. It opens the Russian fixture viewer using separate inspection configuration/workspace. Its Xvfb smoke and actual screenshot are recorded in `overview-viewer-03/manual-smoke.json` and `manual-demo.png`. The distribution archive is `overview-build/MCP-EDT.vdev.zip`; its class payloads match the native-tested bundle. No normal EDT installation was changed and nothing was pushed.

## Recorded Linux evidence

The final bounded run is `/home/ownicc/Workstation/Kasl-diff-test/method-viewer-10/`; [summary.json](summary.json) pins its raw results and source hashes. All 95 ranges/move comparisons passed, 100 ordinary reviews and 20 close-during-compute reviews completed, and zero disposed review roots survived diagnostic GC. The 250000-space comment opened, computed, painted and closed in about 393 ms in this run; this is the whole viewer operation, not an isolated diff benchmark. The first operation with both theme captures took about 325 ms.

Initial trial runs deliberately retained their failures. Full editor cycles exposed a teardown omission that the earlier bare-widget run missed: overriding createContents had bypassed the base disposal hook. The final probe checks this path and the fix; earlier one-root failures are not presented as successful evidence.

Whole-process file descriptors increased from 1896 to 2904 during late EDT JAR/service initialization, then plateaued across the remaining recorded cycles. Tracked live controls decreased from 216 to 150 as an unrelated startup shell closed; tracked Font/Image/TextLayout counts did not increase per cycle. These are bounded observations with platform activity, not an algorithm-only retained-memory estimate.

A real MCP proposal for the copied KASL project returned awaitingApproval, applied=false and mutationCommitted=false; the original file and Git state remained unchanged. Protocol conformance passed its existing baseline. All 165 source-snapshot files in both original and copied KASL, plus all 60 preserved native experiment artifacts, matched their prior hashes. A manual launcher at `/home/ownicc/Workstation/Kasl-diff-test/open-edt-jgit.sh` uses a separate configuration with the test fragment removed; it is not started automatically.
