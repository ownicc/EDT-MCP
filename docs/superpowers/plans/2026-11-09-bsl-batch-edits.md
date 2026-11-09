# Batch Method Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace three named methods with one hash, one review and one write.
**Architecture:** Add strict batch parsing and composition inside WriteModuleSourceTool; reuse the existing single-method validator, preparation/review/commit and receipt store.
**Tech Stack:** Java 17, Gson, Eclipse/EDT, JUnit4 and Python MCP harness.
**Spec:** docs/superpowers/specs/2026-11-09-bsl-batch-edits-design.md

## Global Constraints

English tool/docs surface; 1–50 edits; 500,000 combined raw source characters; one module expectedHash required; human Apply; no new dependencies. Local Worktrunk branch, preserve user changes, GUI only in Xvfb, no push/PR.

## Review Focus

- A bad later edit must not commit an earlier one or create a partial review.
- Duplicate/case-variant targets and malformed array members must refuse.
- Earlier edits cannot shift the original ranges used by later edits.
- Batch-only fields must enter requestId digest without changing legacy digests.
- Both direct writes and pending reviews must use the same combined proposal and late hash guard.

## Task: Deliver batch replacement

Files: WriteModuleSourceTool.java; new WriteModuleSourceBatchTest.java; write_module_source guide/generated docs; tests/e2e/tools/test_write_module_source.py and tools_list.golden.json.

- [ ] Run existing writer/preparation/receipt tests as baseline.
- [ ] Add failing tests for three replacements (including Cyrillic, different lengths and preserved BOM/CRLF), invalid late entry/duplicate/malformed/limits/conflicting selectors/stale hash and receipt conflict/replay. Run RED.
- [ ] Implement strict edits parsing, conditional source requirement, original-span batch composition, normalization, late CAS and digest integration. Verify focused GREEN with existing writer/review regressions.
- [ ] Add live batch success+replay, all-or-nothing refusal and stale-hash e2e; exercise one combined pending proposal and cancellation/acceptance using existing registry/workbench fixtures.
- [ ] Update guide/README and generate actual docs/golden. Canonical build, agent validator, isolated live writer e2e and conformance; inspect known baseline limits without expanding scope.
- [ ] Fresh whole-branch review, record results, update Beads and integrate locally into master after verification.
