# BSL diff search implementation plan

> Execute inline with superpowers:executing-plans; reuse the existing worktree.

**Goal:** find and navigate text on either or both original snapshots.
**Architecture:** JFace Document search adapter collects literal matches; a small
SWT search bar owns UI state; the existing viewer maps matches to aligned rows.
**Tech Stack:** Java 25, JFace, SWT, existing JUnit/Tycho and native probe.
**Spec:** `docs/superpowers/specs/2026-11-09-bsl-diff-search-design.md`.

## Constraints and review focus

No dependencies, source writes, new workspace or launcher. Preserve selected
method, keyboard/read-only behaviour, synthetic padding and UTF-16 coordinates.
Check Cyrillic/case, empty/no results, both sides, wrap/backwards and disposal.

## Task 1 — search model and controls

Files: create `ui/BslDiffSearch.java` and `ui/BslDiffSearchBar.java`, modify
`ui/BslStructuralCompare.java`, `ui/BslDiffOverview.java`, UI messages;
test `ui/BslDiffSearchTest.java` under the existing test source root.

- [x] Write failing tests for literal Cyrillic/case, side scopes, physical
  coordinates with padding, wrap/backwards and empty/no results.
- [x] Implement immutable matches and shared bar using Document search adapter.
- [x] Wire original-to-display line mapping, selected-range highlight and shared
  scrolling; Ctrl+F/F3/Esc for source controls and search bar.
- [x] Run focused tests and actual SWT probe of scope/navigation, unchanged
  selected method, read-only method tab and large-font gutter; inspect capture.
- [ ] Commit and merge locally to master; update Beads and report evidence.

Deployment of this iteration is separate from local validation: preserve the
currently open Kasl review until the new controls are verified.
