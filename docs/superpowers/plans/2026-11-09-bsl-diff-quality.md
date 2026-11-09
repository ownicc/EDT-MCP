# BSL diff quality implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline, then a fresh whole-branch review.

**Goal:** Correct real BSL moves and highlight ranges, preserve readable syntax,
and compare a selected moved method in its own read-only tab.

**Architecture:** Match detached EDT method ranges before bounded line/token
diffing. Reuse paired navigation and the SWT renderer for method-only comparison.
No change to prepared write bytes or MCP comparison reports.

**Tech Stack:** Existing EDT parser, JGit, SWT/JFace, JUnit 4, Tycho on JDK 25.

**Spec:** `docs/superpowers/specs/2026-11-09-bsl-diff-quality-design.md`.
The conversation authorizes implementation of the recommendation; execute without
another permission round.

## Global constraints

- No new runtime dependencies; preserve opt-in jgit and standard compare default.
- Retain source order, 524288 UTF-16 / 20000 lines limits, cancellation/budget.
- Preserve write approval/commit guards. Method-only tab is read-only.
- Automated native UI checks use Xvfb. Visible demo reuses existing copied KASL.
- Work locally in feat/bsl-diff-quality, merge into master after verification;
  no push/PR; preserve concurrent .gitignore change and open-in-editor feature.

## Review focus

- Duplicate methods across conditional branches must never be paired arbitrarily.
- Added/removed region markers must not mark every later method moved.
- Multiline strings/comments containing directive-looking text are not directives.
- Method-only tab retains original coordinates and cannot apply a partial write.
- Styling of whole added methods must retain lexical colours in both themes.

## Task 1: Method matching and exact ranges

**Files:** `ui/MethodAwareBslDiff.java`, `ui/MethodAwareBslDiffTest.java`,
native tests/probe under `experiments/bsl-jgit-viewer` as necessary.
**Interface:** Keep Comparison/Move/Span consumers working; carry exact method
identity/ranges for paired navigation and method-only extraction.

- [x] Establish focused baseline; add real-parser regressions for the spec cases.
- [x] Observe failures for conditional/region moves and unchanged next headers.
- [x] Replace global preprocessor/region fallback with validated method projection,
  preserve conservative ambiguous/malformed fallback, track actual region and conditional branch paths.
- [x] Anchor fine token differences inside local line edits; verify repeated
  prefixes, moved+edited functions, multiline string opening/continuation clipping,
  Cyrillic/BOM/CRLF and source preservation.
- [x] Run focused tests; commit the algorithm change.

## Task 2: Readable viewer and method-only tab

**Files:** `ui/BslStructuralCompare.java`, `ui/BslDiffOverview.java`, `ui/Messages.java`,
`ui/messages*.properties`, `ui/BslDiffThemeTest.java`, native SWT probe.
**Interface:** Existing move badges jump; a selected-move callback enables
«Сравнить метод». Reuse BslStructuralCompare on detached method text in a separate
CompareEditorInput, label source offsets, expose no approval controls there.

- [x] Add failing checks for syntax-preserving changes and selected move action.
- [x] Remove forced diff foreground/bold/underline, retain readable subtle whole
  block backgrounds and stronger local fragments; update both themes.
- [x] Add selection callback/action and read-only method tab with original line
  coordinates; reuse current parser/viewer, disposal and cancellation behaviour.
- [x] Native checks: paired jump, selected moved+edited method comparison,
  source line labels, missing selection disabled, no write action; commit.

## Task 3: Verification and attended KASL example

**Files:** experiment README, report under `docs/superpowers/reports`, Beads epic.

- [x] Run complete Maven/Tycho verify and native Xvfb regression corpus, adjusting
  frozen expectations only for intentional corrections with independent assertions.
- [x] Fresh whole-branch review; reproduce/fix material findings and rerun affected
  checks. Update plan/ledger/evidence and appropriate Beads tasks.
- [x] Commit, merge locally into master, preserve unrelated working changes.
- [ ] Install the verified build into the existing Kasl EDT installation (user
  selected /home/ownicc/.local/share/1C/1cedtstart/projects/Kasl), show the complex example,
  verify original hashes and leave changes awaiting human approval; show git diff.
