# BSL Diff Budget Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan.

**Goal:** Make the BSL comparison budget configurable with a ten-second default
and report fallback elapsed time.

**Architecture:** Reuse preference initialization, GeneralTab and the existing
background comparison. Pass the saved budget into the diff engine and retain
the existing fallback callback.

**Tech Stack:** Java, Eclipse preferences, SWT, JUnit, Tycho.

**Spec:** `docs/superpowers/specs/2026-11-13-bsl-diff-budget-design.md`

## Global Constraints

- Default 10 seconds; UI range 1–60 seconds.
- Preserve 524288 UTF-16 units and 20000 lines per side.
- No new dependencies, Comfort hooks, deployment or release.
- Develop on a local branch and merge verified changes into local master.

## Review Focus

- Unsaved edits and Restore Defaults do not affect running comparisons.
- Out-of-range stored budgets use the nearest bound, matching the native spinner.
- Expired budgets and cancellation stop before parser entry when already known.
- Both synchronous and background failures retain their reason and elapsed time.
- Difftastic keeps its separate existing timeout and execution path.

## Task 1: Preference and engine budget

- [x] Add regression checks in GeneralTabTest and PreferenceInitializerTest for
  initialization, saved values, unsaved edits and defaults. Add engine tests for
  configured expiry and cancellation.
- [x] Run focused tests under Xvfb and confirm the missing preference/control/API
  causes failures before changing production code.
- [x] Add preference constants/default, localized spinner and engine budget
  propagation in PreferenceConstants, PreferenceInitializer, GeneralTab,
  preference Messages/locales, MethodAwareBslDiff and BslStructuralCompare.
- [x] Verify focused tests, including unchanged size limits and parser behavior.

## Task 2: Fallback reporting and completion

- [x] Add a localized elapsed-time template and test synchronous and background
  fallback reporting through real comparison controls where practical.
- [x] Update BslWriteReviewInput and BslStructuralCompare fallback messages and
  document the setting in README and the viewer experiment README.
- [x] Run nearest affected tests, locale checks and git diff --check. Review the
  whole change, commit and merge into local master.

## Verification evidence

- The first focused reactor build failed on the missing configurable budget
  constructor and timeout message, before production changes.
- Focused preference, SWT, locale and engine checks: 52 tests, no failures or
  errors, no skips.
- The added workbench fallback test first failed because the real oversized-input
  fallback lacked elapsed time. Both real fallback paths then passed: oversized
  input before Job creation and missing Difftastic executable in the background.
  The two workbench tests passed with no skips and never applied a proposal.
- Bounded BSL and settings regressions: 395 tests, no failures or errors, eight
  skips. The separate workbench run covers the relevant opt-in UI test.
- Logs: `/tmp/edt-mcp-bsl-budget/`. The partial test workbench still logs the
  previously documented EDT QL injector initialization failure; the actual
  comparison and read-only lifecycle assertions pass.
- Out-of-range stored values use the nearest 1–60 bound, matching SWT Spinner,
  rather than introducing a second preference validation policy.
- Independent review found no actionable issue in the final implementation.
