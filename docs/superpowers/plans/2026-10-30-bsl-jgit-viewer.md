# Method-aware JGit EDT viewer implementation plan

Goal: validate the accepted EDT parser + public JGit approach in the existing detached SWT review viewer (edt-spa), with the approved preview03 appearance.

The user authorized execution of the integration and validation on 2026-10-30. The accepted reference is method-highlight-preview-03/preview.html; the experiment and edt-spa are the design authority. Progress and decisions live in Beads, not in this document.

Architecture: add a detached MethodAwareBslDiff adapter returning existing line/span DTOs plus move and fallback metadata. Opt in with edt.mcp.bslDiff=jgit. Keep the native adapter and its property intact. Reuse the SWT viewer, background Job, existing Apply/Reject registry and exact-byte guards. No new dependencies or JGit fork.

## Constraints

- Preserve native binary, installation, outputs and source baseline; stage a separate installation for this viewer.
- Medium-contrast light palette from preview03; line backgrounds, bold exact fragments, thin underline, original line numbers and +/- gutters, blue moves, neutral hatch padding, selectable real whitespace.
- Only detached parsing; no BM/workspace mutation during comparison.
- Limit each input to 524288 UTF-16 units and 20000 lines; larger input uses the standard comparison with an explicit notice. Cancellation at parser boundaries and during diff, never claim hard mid-parse cancellation. Serialize JGit viewer jobs to avoid accumulating concurrent parses.
- Standard mode and native opt-in remain available until validation supports promotion.
- Never automate acceptance of a review. Test existing commit guards with temporary fixtures/unit tests only.

## Adapter and correctness

Create MethodAwareBslDiff.java beside StructuralBslDiff.java. Port only the verified token/method matching from MethodProbe, excluding benchmarks and JSON. Retain ambiguous identity/preprocessor/region flat fallback and syntax-error text fallback. Map UTF-16 token ranges to physical lines; preserve CR/LF/CRLF, BOM, Unicode, empty/final lines. Return exact-text-change flag independently from structural spans. Add focused JUnit coverage before implementation and compare the port against all 95 frozen experiment inputs in real Equinox.

## SWT rendering and lifecycle

Extend BslStructuralCompare and BslWriteReviewInput to select the opt-in adapter and display moves/fallback/formatting-only state. Keep all write behavior unchanged. Apply the accepted colors; draw whitespace markers without replacing characters. Cancel jobs on dispose and discard results after disposal. Check line style, navigation, selection, scrolling and both themes in a visible EDT.

## Verification

Canonical source/compile.sh build and unit suite; existing reviewed-write/commit tests; agent-pack validator. Use a test-only startup bundle in a separate copied EDT installation to run repeated real viewer open/compute/dispose cycles, collect heap/GC/RSS/FD/thread and SWT resource evidence against a warm visible baseline, capture actual widget rendering and leave a pending real review for inspection. Preserve source hashes. Record platform limitations (only Linux/EDT 2026.2 installed here) and cancellation limits explicitly. Product release certification remains separate.
