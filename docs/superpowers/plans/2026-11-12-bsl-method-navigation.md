# BSL Method Navigation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement the approved changed-method sidebar in the native BSL viewer.

**Architecture:** Derive navigation entries from MethodAwareBslDiff output. A SWT
table owns filtering and delegates selection/jumps to the existing overview.

**Tech Stack:** Java 17, Eclipse SWT/JFace, existing EDT parser, JUnit 4/Tycho.

**Spec:** `docs/superpowers/specs/2026-11-12-bsl-method-navigation.md`

## Global Constraints

- Reuse existing parser identities, diff spans, move pairs and method comparison.
- Do not recompute highlighting or alter source ordering.
- Native UI tests run under Xvfb; no user-desktop test windows.
- Local working branch, verified local merge into master; no push or PR.

## Review Focus

- One moved method must never appear twice or accrue edits from its move spans.
- Duplicate names in conditional branches must show fallback, never guessed pairs.
- Name/type filtering must preserve selection identity and existing diff navigation.
- Outside-method edits and added/removed methods must jump to valid display rows.
- Formatting-only changes, empty results and asynchronous disposal must stay usable.

### Task 1: Navigation model

**Files:** Create `BslMethodNavigation.java` in the production `ui` package and
`BslMethodNavigationTest.java` in the test `ui` package.

**Interfaces:** `entries(Result, List<MethodChange>, String fallback)` produces
immutable entries with method identity, first display row, changed row set and
actual added/removed line counts. `matches(Entry, String, Set<MethodKind>)` applies
combined filters; moved-and-changed matches either relevant kind.

- [x] Write tests using real comparisons for moves, edits, one-sided methods,
  outside changes, duplicate identity fallback, formatting, Unicode search.
- [x] Run tests and observe missing-model failure.
- [x] Implement projection, first-edit navigation and combined filters.
- [x] Run model tests successfully and commit.

### Task 2: Native sidebar and synchronized navigation

**Files:** Create `BslMethodNavigationPanel.java`; modify `BslStructuralCompare.java`,
`BslDiffOverview.java`, `Messages.java`, both `ui/messages*.properties`; add
`BslMethodNavigationUiTest.java` and update README viewer documentation.

**Interfaces:** Panel consumes Task 1 entries, calls overview selection, opens
existing method comparison on default selection. Overview reports selected row
and method to keep caret/change navigation and sidebar selection synchronized.

- [x] Add SWT interaction checks for filter/selection, first-edit jump, outside
  and fallback, default selection, collapse and theme.
- [x] Run and observe missing-panel failure.
- [x] Implement sidebar and connect existing overview/method comparison.
- [x] Run focused tests under Xvfb; inspect native rendering.
- [x] Run full local Maven/Tycho verify and diff hygiene checks, review change,
  commit and merge locally into master. Record Windows verification limitation.
