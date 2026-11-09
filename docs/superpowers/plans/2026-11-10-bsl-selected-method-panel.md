# Selected method panel implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement approved panel C for every changed method, including empty endpoints.
**Architecture:** Reuse the existing unique AST method projection and move detection. Export immutable method ranges and a change kind; keep move decorations separate from selected-method state.
**Tech Stack:** Java, EDT BSL parser, JGit, SWT/JFace, JUnit/Tycho.
**Spec:** `docs/superpowers/specs/2026-11-10-bsl-selected-method-panel-design.md`.

## Global Constraints

- Work in existing local feature worktree; merge locally into master after verification. No push.
- No second parser, guessed pairings, new dependencies, editing or Apply in detached method tabs.
- Keep search selection guard, original source coordinates, move jumps and compact gutter.
- Install into existing Kasl workspace; launch with requested GTK_THEME and NVIDIA EGL variables.

## Review Focus

- Raw formatting-only changes must remain selectable (Task 1).
- Syntax errors and duplicate identities must not expose guessed methods (Task 1).
- Empty side must have no fictitious source line; detached contents remain read-only (Task 2).
- Search must preserve selection; synthetic padding must not select a method (Task 2 native probe).
- Long names must leave the action visible; selected method must follow either source side (Task 2 native probe).

### Task 1: Export all method changes

**Files:** `MethodAwareBslDiff.java`, `MethodAwareBslDiffTest.java` in existing UI source/test packages.
**Interfaces:** Add `MethodKind { CHANGED, MOVED, MOVED_CHANGED, ADDED, REMOVED }`, `MethodChange(String key, List<Span> left, List<Span> right, MethodKind kind)`, `Comparison.methods()` and `Plan.methods()`.

- [x] Write tests asserting five kinds, unchanged omission, exact fragments, formatting-only inclusion, malformed/duplicate exclusion; watch RED.
- [x] Build metadata from existing Part maps/moved set with shared source Documents; preserve existing Move result and guards.
- [x] Run focused Tycho verification (MethodAwareBslDiffTest, BslMethodCompareInputTest, BslDiffThemeTest, BslDiffSearchTest); expect zero failures; commit.

### Task 2: Selection, row C and detached endpoints

**Files:** `BslDiffOverview.java`, `BslStructuralCompare.java`, `BslMethodCompareInput.java`, `Messages.java`, both message bundles, `BslMethodCompareInputTest.java`, `experiments/bsl-jgit-viewer/QualityProbe.java`.
**Interfaces:** Overview `show(Result,List<Move>,List<MethodChange>)`, `selectedMethod()`, `onMethodSelection(Consumer<MethodChange>)`; input ctor consumes MethodChange. Empty fragment uses text empty, coordinates 0/0; user labels show absent/—.

- [x] Write empty-side input tests and native probe assertions for regular edits, added/deleted selection, padding, search preservation, panel contents and long name; watch RED.
- [x] Select by original source coordinates. Retain separate hunk navigation and guard programmatic carets. Implement separate method row with role, name, kind, ranges, Compare action; hide it in method tabs.
- [x] Run focused tests and isolated native probe; expect all assertions pass; commit.

### Task 3: Review, integration and Kasl demonstration

- [x] Run full Maven verify once for shared model/UI behavior; review branch with fresh reviewer, resolve actionable defects.
- [x] Merge locally master; freeze tested p2 repository. Exit existing EDT cleanly; install verified artifact and relaunch existing Kasl with requested environment.
- [x] Create pending review containing several moved methods plus ordinary edits and added/deleted methods; show panel C and correct detached tab. Do not Apply. Record artifact hash and source unchanged proof.
- [x] Update report and beads with actual verification and installation result.
