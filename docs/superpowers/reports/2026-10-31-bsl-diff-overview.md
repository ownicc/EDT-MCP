# Aligned Java/SWT BSL diff

The Java direction selected on 2026-10-31 is implemented in the existing detached structural review viewer. Alignment padding stays. One Canvas scrollbar combines the viewport thumb and red/green/blue change marks. Both StyledText panes retain the same aligned position during wheel, keyboard, thumb, track and hunk navigation. The standard comparison remains the default; `-Dedt.mcp.bslDiff=jgit` selects this viewer. The native difftastic opt-in is preserved independently.

Moved methods expose paired M1/M2 endpoints. A dedicated gutter keeps each clickable badge visible for long signatures and horizontal scrolling. Clicking it jumps to the other endpoint; short declarations also show its source line, and hovering the badge exposes the line. Previous/next groups adjacent changed rows and visits move endpoints. Formatting-only text differences retain their notice without structural marks. SWT selection keeps real tabs/spaces. The Canvas exposes a scrollbar accessibility role, value and keyboard controls.

The compact-only projection, alignment checkbox and prototype property were retired before commit. Their uncommitted source was archived beside the original compact evidence at `/home/ownicc/Workstation/Kasl-diff-test/compact-source-archive/`. No external viewer, grammar or process is required by the Java solution. Prepared write bytes, approval/commit guards, input limits and parser cancellation boundaries are unchanged.

## Verification

Evidence root: `/home/ownicc/Workstation/Kasl-diff-test/overview-viewer-03/`.

- Canonical `source/compile.sh` clean build: 12025 tests, zero failures/errors, five skips; `full-build.log`. This run preceded the final two UI-only review fixes.
- Final Maven `verify -Dtest=BslDiff*Test,StructuralBslDiffTest`: 12 tests, zero failures/errors/skips; `final-build.log`. Rebuilt the final plugin and p2 repository.
- Final distribution repackaged with `source/compile.sh --skip-tests` after the successful checks; `package.log`. Every packaged class payload matches the native-tested plugin. Local archive: `/home/ownicc/Workstation/Kasl-diff-test/overview-build/MCP-EDT.vdev.zip`.
- Final real EDT 2026.2.2.21 / JDK 25 on Linux, isolated Xvfb: all 95 frozen comparisons match exact token ranges, flat move ranges, paired move ranges, fallback reasons and full source-line coverage. `viewer-results.json` records the focused interactions.
- Native SWT checks: exact aligned lines and original numbers; one combined scrollbar; mark clicks, thumb drag to the bottom, wheel and keyboard; paired endpoint jumps with equal scroll positions; navigation wrap; tabs; both themes and resize; empty source sides; formatting-only notice; 250000-space changed line; three close-during-compute reviews; oversized standard-view fallback. Every completed review close rejects; no automated Apply is performed and the fixture commit callback would fail if invoked.
- Review found two concrete issues: long headers lost their inline badge, and tall marks only accepted clicks near their start. Both were reproduced in `overview-review-red-02` and fixed. Final native checks show `tallMarkClickable=true` and `longHeaderBadgeVisible=true`. A newly opened long-header test now forces a real paint/capture before inspecting its hit target; `overview-viewer-02` preserves that prior probe timing failure.
- Actual captures inspected: `overview-move-light.png`, `overview-move-target.png`, `overview-delete-small-dark.png`, and `regression-long-header.png`.
- Manual launcher `/home/ownicc/Workstation/Kasl-diff-test/open-edt-overview.sh` passed bash syntax and a separate isolated Xvfb smoke. Its Russian demo window was found after 5.5 seconds; `manual-smoke.json`, `manual-demo.png`. It uses separate inspection configuration, workspace and output. All owned test EDT/Xvfb processes were closed.
- Startup logs retain an environment warning about missing `dpkg`; no new SWT disposed-widget or invalid-range exceptions were observed. The expected oversized-input refusal is logged by its dedicated check.

The existing 100-cycle/20-cancellation memory evidence belongs to `method-viewer-10`; it was not rerun or presented as a new measurement. Windows/macOS, older EDT and broader product release/e2e certification remain under edt-spa. This work does not change the user's normal EDT installation or publish anything.
