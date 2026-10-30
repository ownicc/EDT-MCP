# Aligned SWT diff overview

Goal: finish the Java/SWT direction explicitly selected on 2026-10-31. The accepted HTML/SWT appearance and the user's acceptance of alignment padding are the design authority. Retain the existing engine opt-in and reviewed-write behavior.

Architecture: reuse the detached aligned StyledText panes and MethodAwareBslDiff move pairs. Replace the compact prototype with BslDiffOverview, one Canvas scrollbar containing change marks and a draggable viewport. Both panes always share the same aligned position. Move badges jump to the other endpoint; navigation groups adjacent changed rows. Native text selection, keyboard navigation and horizontal scrolling remain available. No new runtime dependencies.

Alternatives considered: external GitComet (working local spike, extra application to maintain); compact SWT panes (working spike, extra projection and scroll complexity); aligned SWT with one shared scrollbar (selected). Archive compact spike source with its existing local evidence before removing its unused product code.

## Task 1: aligned overview and verification

- [x] Add focused native SWT checks: aligned source coverage and padding; one integrated scrollbar; track click, thumb drag, wheel and keyboard; paired move jumps without misaligning panes; grouped navigation; tabs, themes, resize, empty sides and formatting-only state. Run against the old implementation and observe the missing shared-scrollbar failure.
- [x] Remove the compact property, projection and alignment toggle. Implement BslDiffOverview and wire it into the existing opt-in viewer. Use the existing immutable comparison result; keep parser and write guards unchanged.
- [x] Run the canonical build/unit suite and focused native Xvfb runner including the 95 frozen comparison fixtures and real review close/reject lifecycle. Inspect actual screenshots and logs. Do not accept any proposal. Check disposal/cancellation and long-source drawing cost.
- [x] Record evidence, review the final diff, commit on the local branch and merge locally into master. No push, PR, release or normal-EDT installation change.

Review focus: zero-length/empty source boundaries; changes at EOF; move origin and destination far apart; text viewport clamp after resize; copying actual whitespace and disposed widget callbacks. Linux EDT evidence does not certify Windows/macOS or older EDT versions. Broader release gates remain tracked by edt-spa.

Review: two actionable issues (badge lost with long headers; tall marks only clickable near their start) reproduced in overview-review-red-02 and fixed. Final native evidence: overview-viewer-03, including dedicated gutter badges and shared paint/hit-test rectangles. The focused probe forces an actual widget capture before asserting a newly opened long-header badge; a prior assertion had run before its first paint. No additional product fix was needed for that probe timing.
