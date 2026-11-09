# Comparison module text diff

> **For agentic workers:** Use superpowers:executing-plans to implement this plan.

**Goal:** Complete Beads edt-mmh.1: expose bounded textual changes from the sides
of a live configuration comparison, without changing its model or merge verdict.

**Architecture:** Capture immutable module text and the existing rendered node
report inside one comparison read. Format the text with installed JGit outside
that boundary. Keep the mutable comparison session confined to ComparisonView.

**Tech Stack:** Java 17 source, EDT comparison API, existing JGit, JUnit 4;
Maven/Tycho runs on the installed JDK 25.

**Spec:** Beads edt-mmh/edt-mmh.1, authorized in the conversation. The user asked
to proceed and show the result; no further design discussion is required.

## Global constraints

- No new dependencies, external viewer, merge operation or workspace source lookup.
- Work in a local branch, verify, then merge locally into master. No push or PR.
- Preserve existing brief output unless `textDiff` is supplied.
- `textDiff`: `main-other`, `ancestor-main`, or `ancestor-other`.
- `contextLines`: 0..10, default 3. `maxDiffLines`: 1..2000, default 200.
- Bound formatted output to 64 KiB and each input to 500000 UTF-16 units / 20000
  lines. These are computation/output bounds; EDT's module reader loads the text.
- A section request uses its whole owning module, explicitly labelled as such.
  Precise section extraction is edt-mmh.2, not an approximation in this change.
- Missing side, empty text, unavailable text and binary module are distinct.
- Three-way pairs are text observations, not independent conflict decisions.
- Do not change the existing viewer, approval guards, or standard viewer default.

## Review focus

- Wrong-side source: different main/other/ancestor streams must yield their own text.
- Read failure must not become an empty module or a claim of equality.
- Output limits must preserve complete UTF-8 lines and state truncation.
- Literal Markdown fences in BSL must not break the report.
- A vanished/unfinished comparison must keep the existing lifetime/status behavior.

## Task 1: Bounded text diff end to end

**Files:**
- Add `utils/compare/ComparisonTextDiff.java`: immutable source observations and
  bounded JGit Markdown rendering.
- Modify `utils/compare/ComparisonView.java`: read module text through EDT's
  public BslCompareUtils and an injected file-path converter, without exposing
  the session. Resolve section ancestors to their containing module.
- Modify `tools/impl/GetComparisonNodeTool.java`: validate options, capture both
  sides within the existing read, render hunks after it closes.
- Test `ComparisonTextDiffTest`, `ComparisonViewTest`, `GetComparisonNodeToolTest`.
- Update the tool guide, generated docs, and tools/list golden.

**Interfaces:** `ComparisonTextDiff.Source` is a detached observation (module
name, text, presence/read outcome); `TreeAccess.moduleText(node, side)` returns
one such observation. `ComparisonTextDiff.render(from, to, labels, context,
limit)` consumes no live model/session objects.

- [x] Write tests for actual hunks/coordinates, all three side pairs, absent vs
  empty vs unreadable, binary source, limits, Cyrillic/BOM/CRLF/final newline and
  embedded fences. Pin unchanged default output and rejection of invalid options.
- [x] Run the tests and observe failure before implementation.
- [x] Implement the source reader, detached capture and bounded formatter.
- [x] Run focused checks and fix only concrete failures.
- [x] Update docs and golden via the established generators/workflow.
- [x] Run the project build/unit suite and relevant docs/golden checks.
- [x] Review the branch, resolve concrete findings, record evidence in Beads.
- [x] Commit, merge locally into master and show the patch and output example.
