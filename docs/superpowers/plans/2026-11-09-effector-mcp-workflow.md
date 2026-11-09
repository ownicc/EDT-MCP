# BSL MCP Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Complete Beads epic edt-0ks without changing human-only write acceptance.

**Architecture:** Extend existing tools and lifecycle helpers. Add a focused read-only document snapshot helper and a bounded persistent write receipt store; reuse the existing metadata module resolver and review registry.

**Tech Stack:** Java 17 source, Eclipse/EDT APIs, Maven/Tycho with JDK 25, JUnit4, Python MCP harness.

**Spec:** `docs/superpowers/specs/2026-11-09-effector-mcp-workflow-design.md`

## Global Constraints

- English tool/docs surface, lowerCamelCase arguments, ToolResult errors.
- Existing disk hashes, write guards and human Apply stay intact.
- No source mutation while reading/cancelling/opening; no new dependencies.
- Automated GUI work only in Xvfb. Local branches only, no PR/push.

## Review Focus

- Dirty documents in other windows and file buffers must not be missed.
- Queued reads after timeout must not touch disposed UI or silently return disk text.
- Cancellation racing Apply must preserve the real mutation outcome.
- Alternate module selectors must retain canonical path and project confinement.
- Interrupted receipts must never trigger automatic duplicate writes, even after restart.

## Task 1: Document reads (edt-0ks.1)

Files: ReadModuleSourceTool, new utils/ModuleDocumentSnapshot, corresponding unit/workbench tests, guide and e2e test.

- [x] Add failing tests for document selection, unavailable/absent buffers, coherent text/dirty/hash, ranges and unchanged disk reads.
- [x] Run focused JUnit tests to verify RED, then implement snapshot lookup using existing buffers/editors and bounded UI dispatch.
- [x] Verify GREEN plus real Xvfb workbench fixture; preserve BOM/line handling and avoid disk-token confusion.
- [x] Update guide/schema tests; commit the tested deliverable.

## Task 2: Review withdrawal (edt-0ks.2)

Files: BslWriteReviews, new CancelWriteReviewTool, registry/toolsets/preferences, lifecycle/tool tests and e2e/guide.

- [x] Add failing cancellation/race tests in the real registry.
- [x] Add atomic pending-only cancellation, then expose the tool using the same snapshot contract as status.
- [x] Cover unknown IDs, repeat and terminal/applying cases, payload/module release and UI listeners.
- [x] Update migrations/read-only classification and verify focused tests; commit.

## Task 3: Editor target addressing (edt-0ks.3)

Files: OpenInEditorTool, shared module target resolver if needed, WriteModuleSourceTool thin delegation, tests/guide/e2e.

- [x] Add failing tests for XOR selectors, ru/en types, form/command and configuration modules using existing resolver fixtures.
- [x] Reuse the existing resolver and retain original modulePath/navigation checks.
- [x] Verify focused tests and dirty-document workbench navigation; commit.

## Task 4: Write request receipts (edt-0ks.4)

Files: new utils/BslWriteRequests, WriteModuleSourceTool, GetWriteReviewStatusTool if needed, lifecycle integration, receipt/tool tests and guide/e2e.

- [x] Add failing tests using temporary on-disk stores: replay, digest conflict, concurrent admission, restart and interrupted receipt.
- [x] Implement admission-before-execution, small durable receipts, review snapshot updates and explicit unknown recovery; never store source text.
- [x] Cover retention/capacity, journal failures and legacy requests; verify both review and direct-write integration.
- [x] Update MCP contract and commit.

## Integration

- [x] Canonical full build and agent validator; generate docs and tools golden from actual tools.
- [ ] Xvfb workbench checks and available isolated live MCP/e2e/conformance checks; inspect logs and report environment limits.
- [ ] Independent whole-branch review, fix material findings with regression checks, update Beads and merge locally after verification.
