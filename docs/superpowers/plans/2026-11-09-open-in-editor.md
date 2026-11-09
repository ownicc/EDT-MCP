# Open in editor implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan.

**Goal:** Open a BSL module and optionally navigate to a method or position in EDT.
**Architecture:** One IMcpTool using the existing module resolver, IDE editor and live IDocument. Keep navigation calculation independently testable with real JFace Documents.
**Tech Stack:** Java 17 source, existing Eclipse/EDT APIs, JUnit 4; build on JDK 25 with Tycho 5.
**Spec:** docs/superpowers/specs/2026-11-09-open-in-editor-design.md

## Global constraints
- Worktrunk branch feat/open-in-editor; BSL only; no source changes or saves; no new dependencies.
- projectName/modulePath required; methodName conflicts with line/column; coordinates are 1-based.

## Review focus
- Unsaved lines and duplicate method names must not select a stale or arbitrary method.
- Column validation must stay on the selected line, including CRLF and empty lines.
- No-position activation preserves existing selection.
- UI waiting must not deadlock an in-process UI caller or execute abandoned queued work.
- Tool hints and read-only presets must reflect UI side effects.

### Task 1: Implement and integrate the tool
**Files:** new tools/impl/OpenInEditorTool.java, corresponding JUnit and E2E tests; BuiltInToolRegistrar, Toolsets, ToolGroup, ToolPreset; guide and tools catalogue.
**Interfaces:** tool inputs above; JSON success includes canonical modulePath, projectName, opened and navigation coordinates when requested. A package-private navigation calculator consumes IDocument and returns a position used by the UI path.
- [x] Add failing behavior tests for registration, validation and live-document navigation (dirty content, ru/en, duplicate names, invalid/CRLF coordinates, no-position).
- [x] Run focused Tycho tests; confirm failure from the missing feature.
- [x] Implement the tool and catalogue/settings/guide/E2E integration.
- [x] Run focused tests, then full `mvn verify` and official `source/compile.sh` packaging.
- [x] Review branch, address material findings, commit and retain Worktrunk branch. Follow project integration policy.

Pre-flight: one task; no shared task interfaces.

## Verification ledger
- Registration test RED: 1 failure at the missing tool, 0 errors (`/tmp/edt-open-editor-red.log`).
- Review regression and saved-profile migration RED: two expected failures, then GREEN after using the existing unaddressable-declaration guard and adding migration 18.
- Focused settings/catalogue/migration checks: 627 tests, 0 failures, 0 errors, 1 skipped (`/tmp/edt-open-editor-settings-all.log`). Historical migration fixtures retain their literal older additions and now account for v18.
- Actual Eclipse workbench under Xvfb: 1 test, 0 failures/errors/skips (`/tmp/edt-open-editor-workbench.log`), including reuse, unsaved source, current caret, no disk save, and UI/background callers.
- Fresh review: one P2 (incomplete declaration census), fixed with a real-document RED/GREEN regression. No deferred minor findings. The reviewer did not execute UI behavior; the subsequent opt-in workbench test covered it.
- Initial baseline was stopped during a bounded comparison wait; final validation includes all classes without exclusions.

- Final official `source/compile.sh` clean build: 12055 tests, 0 failures/errors, 6 skipped. Archive assembled and checked for the new tool bytecode and guide. No test classes excluded.
