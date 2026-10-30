# Opt-in method-aware JGit SWT review validation

The existing detached review editor now accepts `-Dedt.mcp.bslDiff=jgit`. It uses the already installed EDT parser and public JGit API, with method reorder metadata separate from exact edit ranges. The standard default and the preserved native deployment remain available. This is the Linux integration/validation part of edt-spa, tracked as edt-spa.1; it is not a product release certification.

## Result and evidence

The final isolated SWT run is `/home/ownicc/Workstation/Kasl-diff-test/method-viewer-10/`. Its raw summary and source fingerprints are pinned in `experiments/bsl-jgit-viewer/summary.json`.

- All 95 frozen experiment cases matched their prior token/move ranges, fallback reason and exact-text-change flag; line alignment covered each source line exactly once in order.
- 100 full CompareEditorInput open/compute/close cycles and 20 close-during-compute cycles completed. Closing rejected fixture reviews; the test callback would fail on any automatic acceptance. Zero disposed review roots survived the separate GC diagnostic.
- Actual SWT captures demonstrate the approved light appearance, dark theme and whitespace overlays. Native selection returned original quote/tab characters. A 250000-space changed comment completed its whole viewer open/compute/paint/dispose operation in about 393 ms, with whitespace work restricted to visible columns. The first operation including both theme captures took about 325 ms. Neither number is an isolated algorithm benchmark.
- Oversized input used the standard viewer and retained the exact 524288 UTF-16 / 20000-line limit notice.
- A real MCP write proposal for copied KASL returned awaitingApproval, applied=false and mutationCommitted=false. The source bytes and Git state stayed unchanged. Official protocol conformance passed the existing baseline.
- Agent-pack validation and the preserved four preview rendering tests passed. The canonical final source/compile.sh build passed 12025 Java tests with zero failures/errors and five expected skips; log: /tmp/edt-jgit-tools/verified-final-build.log.
- All 165 snapshot files in original KASL and all 165 in copied KASL still matched their saved hashes; all 60 native experiment artifacts were unchanged.

The initial visible test caused repeated diff switches on the user's desktop. That run was stopped; subsequent automated runs use Xvfb. The final virtual EDT and test display were closed after verification. The separate manual launcher `Kasl-diff-test/open-edt-jgit.sh` has the startup test fragment removed and is not launched automatically.

## Findings fixed

The first independent review found unbounded offscreen whitespace painting and loss of the oversized-input reason. Both were fixed and exercised by the final UI probe. The probe also found an actual lifecycle omission: the custom createContents override bypassed CompareEditorInput.handleDispose. Cleanup now invokes that hook after the final child, is idempotent, and the readiness future retains a weak control reference. The earlier one-root failures are preserved in trial outputs; only final run10 has zero surviving roots.

A focused regression also demonstrated that CR-only lines could hide a conditional directive after a region header; physical line splitting now recognizes CR, LF and CRLF. The red/green JUnit logs are under `/tmp/edt-jgit-tools/`.

## Limits and resource interpretation

The input cap and two-second comparison budget cannot guarantee interruption inside IParser.parse; cancellation is observed at parser boundaries and inside diffs. Method ownership remains conservative; no fuzzy rename or general AST matcher was added.

Whole-process descriptors increased from 1896 to 2904 during late EDT service/JAR initialization, then plateaued through the remaining cycles. Tracked Controls decreased 216 to150 when an unrelated startup shell closed; Font/Image/TextLayout counts did not grow per cycle. The post-run FD inventory is retained. SWT tracking began after the workbench existed, so preexisting resources are not enumerated. RSS/heap snapshots include service initialization, retained test evidence and JVM activity: no peak subtraction or algorithm-only retained-memory claim is made.

Only Linux x86_64 / EDT2026.2.2.21 / JDK25.0.4.1 was executed. Older EDT, Windows/macOS and attended scrolling/selection still need suitable environments. The complete product e2e suite with its dedicated TestConfiguration stand was not run in this isolated KASL viewer trial. No tools/list change, release, push or PR was made.
