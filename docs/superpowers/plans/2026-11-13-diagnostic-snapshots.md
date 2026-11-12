# Diagnostic Snapshots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Compare complete EDT diagnostic snapshots and report new, fixed and remaining problems after edits.

**Architecture:** Keep immutable diagnostic values and a bounded snapshot store in `utils/DiagnosticSnapshots.java`. Capture through shared marker interpretation and BM read boundaries in `utils/DiagnosticSnapshotCapture.java`. Expose capture/comparison through one read-only `ProblemSnapshotsTool`, preserving existing listing tools.

**Tech Stack:** Java 17, existing Eclipse/EDT APIs, Gson, JUnit4/Mockito, Python stdlib e2e harness.

**Spec:** `docs/superpowers/specs/2026-11-13-diagnostic-snapshots-design.md`

## Global Constraints

- Retain the last 10 successful captures in process memory; at most 10,000 matching problems per snapshot.
- Match signature multisets, excluding line/offset/time; disclose ambiguous identical occurrences.
- Compare immutable scopes; use `limit=100`, maximum 1000, and nonnegative `offset`.
- Refuse incomplete captures; no automatic validation, saving, source changes or new dependencies.
- Work on the local feature branch, then merge into local `master` after required verification. Do not publish working branches or create PRs.

## Review Focus

- Equal totals hiding regressions: assert one new and one fixed signature.
- Identical duplicates and line shifts: assert correct multiplicity and before/after locations.
- Missing, unreadable, closed or overflowing captures: refuse without storing or evicting a snapshot.
- Scope normalization and delimiter collisions: compare typed keys, reject incompatible scopes.
- Pagination and retention: stable pages/full counts, unknown/evicted IDs give actionable errors.

## Task 1: Immutable snapshot comparison and retention

**Files:** create `utils/DiagnosticSnapshots.java` under the server source package and `utils/DiagnosticSnapshotsTest.java` under its test fragment.

**Interfaces:** `Scope`, `Diagnostic`, `Snapshot` immutable records; `capture(Scope, List<Diagnostic>)`, `compare(String baselineId, String snapshotId, int limit, int offset)`; shared store for runtime, isolated instances for tests.

- [x] Write failing tests for equal totals/different signatures, shifts, duplicates, severities/messages/locations, normalized scopes, immutable data, overflow/retention and paging.
- [x] Run the focused Maven test and observe the missing implementation failure.
- [x] Implement bounded retention and deterministic multiset comparison using typed keys.
- [x] Run `DiagnosticSnapshotsTest`; require all cases green.

## Task 2: Capture and MCP contract

**Files:** create `utils/DiagnosticSnapshotCapture.java`, `tools/impl/ProblemSnapshotsTool.java`, their unit tests and `guides/problem_snapshots.md`. Modify `BuiltInToolRegistrar`, `Toolsets` and `preferences/ToolGroup`.

**Interfaces:** consume Task 1 values/store; collector resolves `ProjectContext`, applies existing marker filters and builds immutable diagnostics inside a BM read transaction. Tool reads `action`, `projectName`, `severity`, `checkId`, `modulePaths`, `baselineId`, `snapshotId`, `limit`, `offset` and returns JSON with an output schema.

- [x] Add failing contract/refusal/capture tests, including unavailable services, unreadable markers, invalid scopes, conditional arguments and pagination values.
- [x] Observe focused test failure, implement collector/tool and add the guide and inventories.
- [x] Verify focused tests plus existing marker-row tests; preserve current tool contracts.

## Task 3: Documentation, live workflow and release gates

**Files:** add `tests/e2e/tools/test_problem_snapshots.py`; update README, generated tool docs/index and the live tools-list golden.

**Interfaces:** use the real MCP tool via the existing Python harness; preserve fixture hygiene.

- [x] Add live capture/compare, scope/refusal/retention/paging checks and a saved-code edit/revalidate/capture comparison with fixture restoration.
- [x] Build the plugin; deploy only to an isolated EDT test installation/workspace. Verify deployed class identity.
- [x] Generate docs/golden from this build and validate agent-pack documentation.
- [x] Run full build/unit, targeted live e2e and protocol conformance; inspect EDT errors. The user explicitly selected targeted e2e plus the full unit suite instead of completing the 1898-test broad e2e run. Parallel stand setup encountered GTK image-loader failures; no production workaround was added.
- [x] Obtain a fresh branch review, resolve actionable findings, verify the final diff and commit.
- [x] Merge the verified branch into local `master`; leave origin untouched unless publishing is requested.

## Verification record

- Canonical `source/compile.sh`: BUILD SUCCESS; 13888 tests, 0 failures, 0 errors, 30 skips.
- Focused Tycho contract/marker tests: 64 passed.
- Live `problem_snapshots` workflow: 5/5 passed; fixture clean.
- Live tools-list golden: 1/1 passed; only the new tool changed the wire catalogue.
- E2E harness unit suite: 319 passed; agent-pack documentation validator passed.
- Official MCP conformance: baseline check passed, no new failures.
- Fresh independent review: no actionable findings.
- Full e2e omitted by the user's explicit verification-scope choice.
