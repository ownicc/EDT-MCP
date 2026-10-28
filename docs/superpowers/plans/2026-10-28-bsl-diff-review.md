# BSL Diff Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require human acceptance of BSL writes in a nonmodal read-only EDT compare tab, with a default-on preference and asynchronous MCP status.

**Architecture:** Prepare immutable output before any mutation, store pending reviews without occupying worker threads, and commit exactly the accepted bytes after revalidation. Use native Eclipse CompareEditorInput with an Apply/Reject header. Keep the existing direct-write response when review is disabled.

**Tech Stack:** Java release 17, Eclipse SWT/JFace/Compare and workspace Jobs, existing Gson and JUnit4/Mockito, Maven/Tycho on JDK25, Python e2e harness. No external dependency is added.

**Spec:** [Approved design](../specs/2026-10-28-bsl-diff-review-design.md).

## Global Constraints

- Both comparison sides are read-only; acceptance is exclusively a UI action.
- Every `write_module_source` mode is covered; metadata tools and unsaved-buffer editing are outside scope.
- `Review BSL changes before applying` defaults to `true`, including upgrades and headless runs.
- No `confirm`, `skipReview`, or agent-writable setting bypass is introduced.
- At most 16 active proposals, at most one per canonical module, a 30-minute decision deadline, and at most 100 retained terminal statuses.
- Bound retained source/output payloads to 16 MiB in total, measured in UTF-8 bytes.
- No workspace rule, BM transaction, SWT wait, or worker thread is held during human review.
- An unsaved target is refused; a changed base is terminal `stale`; no automatic merge occurs.
- Existing normalized hashes, bilingual resolution, vendor support, BOM/line endings, syntax checks, and adopted-module follow-up remain intact.
- JSON uses lowerCamelCase and errors use `ToolResult`; production prose remains English, with existing UI NLS conventions respected.
- Work in an isolated development worktree when execution starts; preserve the approved design and this plan in it. Never modify the user's installed EDT or push autonomously.

## Review Focus

- A preference toggle between execution and response serialization must not mislabel JSON as Markdown: Task 1.
- Human typing in another workbench window just before acceptance must not be overwritten or implicitly saved: Task 3.
- Closing/reopening a project with the same name/path must invalidate its old proposals: Tasks 2 and 6.
- Apply arriving after the deadline but before the expiry timer runs must not write: Task 2.
- Post-write adopted-module/export failure must report that source was written, so an agent does not retry an append: Tasks 3 and 7.

## File and interface map

For compact paths below, `S/` expands to
`mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/`,
`T/` to `mcp/tests/com.ditrix.edt.mcp.server.tests/src/com/ditrix/edt/mcp/server/`,
and `B/` to `mcp/bundles/com.ditrix.edt.mcp.server/`.

| File | Responsibility |
| --- | --- |
| `S/tools/IMcpTool.java`, `S/protocol/McpProtocolHandler.java` | Select delivery format from the actual completed result without rereading settings |
| `S/utils/PreparedBslWrite.java` (new) | Immutable identity, source snapshot, accepted bytes, and response facts |
| `S/utils/BslWriteReviews.java` (new) | Bounded lifecycle, project/server generations, deadline, deduplication, and status |
| `S/utils/BslWriteReviewCommit.java` (new) | Target revalidation, unsaved-buffer guard, serialized write, and outcome tracking |
| `S/ui/BslWriteReviewInput.java` (new) | Native compare controls and header actions |
| `S/ui/BslWriteReviewUi.java` (new) | Bounded UI opening, focus, subscriptions, and cleanup |
| `S/tools/impl/WriteModuleSourceTool.java` | Prepare once, select review/direct path, and reuse existing post-write result logic |
| `S/tools/impl/GetWriteReviewStatusTool.java` (new) | Read-only polling by reviewId |
| Existing preference, registration, lifecycle, guides, and test files | Opt-out UI, discovery, cleanup, and validation |

Use nested records/enums in the responsible class rather than additional files for every state or result. Do not extract unrelated portions of the existing write tool.

## Verification commands

The repository's supported Java verification is `bash source/compile.sh` from the root. Discover JDK25/Maven paths at execution time and pass `--java-home` / `--maven-home` when necessary; do not commit machine paths. The script does not accept arbitrary Maven test selectors. Run the canonical build for each task's red/green cycle and inspect the relevant JUnit reports; unrelated failures do not constitute the expected red result.

Documentation-only checks are `git diff --check` and review of staged changes. Do not claim live UI behavior from unit tests.

---

### Task 1: Bind response delivery to the result of this call

**Files:** Modify `S/tools/IMcpTool.java`, `S/protocol/McpProtocolHandler.java`; test `T/protocol/McpProtocolHandlerTest.java`.

**Interfaces:** Add default `ResponseType IMcpTool.getResponseType(Map<String,String> params, String result)` delegating to the existing one-argument method. `buildToolCallResponse` calls this overload. No new wire argument or tool-name special case is added.

- [ ] Write a failing protocol test with a stub whose completed result is JSON but whose mutable preference becomes false before serialization; assert `structuredContent.reviewId` is preserved. Also pin existing fixed types and argument-selected formats to their old wire envelopes.

  Name it `resultDeliveryUsesCompletedPayloadAfterPreferenceToggle`; its wire assertions include:
  ```java
  assertEquals(id, response.getAsJsonObject("result")
      .getAsJsonObject("structuredContent").get("reviewId").getAsString());
  ```
- [ ] Run `bash source/compile.sh`; expect the new result-dependent delivery test to fail, with existing tests otherwise unchanged.
- [ ] Add the default overload and update the response dispatch call. Preserve error diversion, capability negotiation, plain-text fallback, signal augmentation, and outputSchema rules.
- [ ] Run `bash source/compile.sh`; require the new tests and existing protocol/type tests to pass.
- [ ] Review the staged diff and commit `refactor: select tool response type from completed result`.

### Task 2: Add immutable prepared writes and the bounded review registry

**Files:** Create `S/utils/PreparedBslWrite.java`, `S/utils/BslWriteReviews.java`; create `T/utils/PreparedBslWriteTest.java`, `T/utils/BslWriteReviewsTest.java`.

**Interfaces:**

- `PreparedBslWrite` stores project name/location/lifecycle token, canonical module path, base existence and full raw SHA-256, base/proposed text, output bytes, before/after line counts, mode, syntax-check flag, and normalization summary. Copy byte arrays defensively. It retains no project, BM object, or transaction.
- `BslWriteReviews.shared()` returns the service for the current server lifetime. A package-visible constructor accepts `Clock` for deterministic tests.
- `Admission register(PreparedBslWrite write, String requestDigest, BiFunction<String,PreparedBslWrite,Outcome> commit)` returns `Snapshot snapshot` plus `boolean reused`, or an actionable admission error. The callback receives the review ID and must capture no model/resource objects. Compute requestDigest from sorted effective public arguments after defaulting and canonical target resolution; include every behavior-affecting argument.
- `Snapshot get(String reviewId)`, `void apply(String reviewId)`, `void reject(String reviewId)`, `void expireDue()`, `void cancelPending(String reason)`, `void beginGeneration()`, and `void close()` own all lifecycle transitions.
- `boolean tryEnterMutation(String reviewId)` atomically competes with shutdown/cancellation after Apply has claimed the review. It succeeds once only for a live `applying` review. Track this boundary separately from State; cancelPending may cancel `applying` work only before that boundary.
- Nested `State`, `Snapshot`, and `Outcome` encode the spec's states. Outcome carries `Boolean mutationCommitted` (null means unknown), result Markdown when present, and error JSON when present. Snapshots expose no source text or callbacks.
- Project lifecycle tokens are maintained by this service from resource close/delete/recreate events; capture and validate tokens at preparation/application. Server generation is separate.

- [ ] Write failing tests: 16 active accepted and seventeenth refused; only one pending per canonical target; matching digest reuses ID; different digest reports `reviewPending`; UTF-8 payload cap; 100 terminal status cap; terminal payload release; deadline at exactly 30 minutes; Apply/Reject/close/expiry races; generation and project replacement invalidation.

  Include `applyAtDeadlineCannotCommitBeforeExpiryTimerRuns` with a fake clock advanced exactly 30 minutes:
  ```java
  reviews.apply(id);
  assertEquals(BslWriteReviews.State.EXPIRED, reviews.get(id).status());
  assertEquals(0, commitCalls.get());
  ```
- [ ] Run `bash source/compile.sh`; expect missing registry/types or failed boundary assertions.
- [ ] Implement immutable data and the registry. Schedule commits only after atomic `awaitingApproval -> applying`; use Eclipse Jobs for executable work and expiry scheduling, with no waiting worker. Check the deadline inside Apply. Retain `applying` ownership until the real outcome is published; repeat Apply is a no-op.
- [ ] Run `bash source/compile.sh`; require deterministic race/boundary tests to pass. Assert no commit callback executes on rejected, expired, stale-generation, or merely polled entries.
- [ ] Review and commit `feat: track bounded BSL write reviews`.

### Task 3: Prepare exact output and commit it under fresh guards

**Files:** Modify `S/tools/impl/WriteModuleSourceTool.java`; create `S/utils/BslWriteReviewCommit.java`; test `T/tools/impl/WriteModuleSourceToolTest.java`, new `T/utils/BslWriteReviewCommitTest.java`.

**Interfaces:**

- Extract `static byte[] encodeOutput(List<String> lines, boolean withBom, String lineDelimiter)` inside the write tool; use it for the unchanged direct write and prepared output.
- Add private preparation in the write tool returning `PreparedBslWrite` from one coherent base read. Continue using its existing `computeNewLines`, syntax checker, path resolution, and preconditions.
- `BslWriteReviewCommit.commit(PreparedBslWrite write, BooleanSupplier enterMutation, Function<PreparedBslWrite,String> afterWrite)` returns `BslWriteReviews.Outcome`. Wire enterMutation to the registry's `tryEnterMutation(reviewId)` and call it immediately before the first filesystem mutation, including creation of parent folders.
- Private `WriteModuleSourceTool.finishReviewedWrite(PreparedBslWrite write)` re-resolves context and invokes existing `finishWrite` / `AdoptedModuleState.ensureExtended`, returning the existing result Markdown/error JSON. Pass a method reference to commit; do not retain an old scope.

- [ ] Write failing tests for exact encoded bytes (BOM/no BOM, CRLF/LF, final newline), coherent base hash, new-file preparation without folder creation, every existing write mode, Unicode identifiers, and unchanged `expectedHash` semantics. Assert the prepared result is not recomputed after approval.

  Include `encodedOutputPreservesCrLfAndFinalNewline` in the existing tool test package:
  ```java
  assertArrayEquals("a\r\nb\r\n".getBytes(StandardCharsets.UTF_8),
      WriteModuleSourceTool.encodeOutput(List.of("a", "b"), false, "\r\n"));
  ```
- [ ] Run `bash source/compile.sh`; require failures at the new preparation/commit seams, not unrelated baseline behavior.
- [ ] Implement preparation and commit. At commit re-resolve via `ProjectContext.of`, `resolveMetadataRoot`, `BslModuleUtils.resolveModuleFile`, and `VendorSupportGuard.refusalForModuleFile`; validate lifecycle/location/containment, vendor state, dirty buffers, existence, and raw digest before writing the stored bytes under a workspace modify rule. Use a parent creation rule where a missing target requires folders. Preserve `KEEP_HISTORY`, and never force-overwrite an unexpectedly created file.
- [ ] Implement a bounded scheduling-rule/UI handoff: acquire rules off SWT, transfer ownership for the short SWT critical section using public Eclipse job-manager APIs, then check all target editor/file-buffer dirtiness and write before another SWT edit event can run. Never `syncExec` while holding a rule SWT may need. Distinguish failure to enter from an in-flight write; follow-up export runs off SWT.
- [ ] Add red/green tests for typing in a second window, dirty-buffer appearance after preview, dirty state with no visible editor, closed/replaced projects, vendor lock changes, creation race, base changes in every mode, rule-acquisition/UI timeout, and write-versus-follow-up failure. Assert `mutationCommitted=true` after successful bytes plus failed export, and null for an unverified write. Run `bash source/compile.sh` to green.
- [ ] Review and commit `feat: prepare and safely commit reviewed BSL writes`. The review feature is not activated yet; direct writes remain available.

### Task 4: Build the read-only native compare tab

**Files:** Create `S/ui/BslWriteReviewInput.java`, `S/ui/BslWriteReviewUi.java`; modify `S/ui/Messages.java`, `S/ui/messages.properties`, and the existing Russian UI resource bundle; modify `B/META-INF/MANIFEST.MF`; create `T/ui/BslWriteReviewInputTest.java`, `T/ui/BslWriteReviewUiTest.java`.

**Interfaces:**

- `BslWriteReviewInput(PreparedBslWrite write, String reviewId, BslWriteReviews reviews)` extends `CompareEditorInput`; its `createContents(Composite parent)` creates the persistent header and normal comparison below it.
- `BslWriteReviewUi.open(PreparedBslWrite write, String reviewId)` confirms actual tab creation within 5 seconds or returns an actionable refusal. Late UI work checks the registry before opening. `focus(String reviewId)` reuses a live tab; `closePending()` disposes pending UI during lifecycle cleanup.
- Header actions call only registry `apply` / `reject`. Registry status subscriptions update the header through `Display.asyncExec` and detach on disposal. No worker remains waiting after open completes.

- [ ] Write failing SWT tests for header/buttons, labeled left/right texts, noneditable sides, no writable comparison elements, no merge/save mutation, and close while awaiting -> rejected. Add Apply double-click, expiry, UI creation failure, delayed creation after timeout, and disposal during applying cases.

  Include `comparisonNeverEnablesEitherEditableSide`:
  ```java
  assertFalse(input.getCompareConfiguration().isLeftEditable());
  assertFalse(input.getCompareConfiguration().isRightEditable());
  ```
- [ ] Run `bash source/compile.sh`; expect missing UI classes/actions to fail. Pure tests may use a controlled display, but record display-dependent cases for the live gate if the headless test runtime cannot run SWT.
- [ ] Implement using public `org.eclipse.compare` APIs and an explicit bundle dependency. Keep inputs detached from live files and editor dirty state false; `Ctrl+S` must not invoke acceptance. Use existing NLS mechanisms for UI labels. No modal dialogs and no auto-apply fallback on UI failure.
- [ ] Run `bash source/compile.sh`; require widget-independent and available SWT tests to pass. On a development EDT copy, verify another editor remains usable, scrolling keeps header visible, and both sides reject typing/paste/merge shortcuts.
- [ ] Review and commit `feat: review BSL changes in a nonmodal compare tab`.

### Task 5: Add polling and the default-on preference, then route writes

**Files:** Modify `S/preferences/PreferenceConstants.java`, `PreferenceInitializer.java`, `ConsentSettingsService.java`, `GeneralTab.java`, `Messages.java`, and existing preference bundles; modify `S/tools/impl/WriteModuleSourceTool.java`, `S/tools/BuiltInToolRegistrar.java`, `Toolsets.java`, `S/preferences/ToolGroup.java` and `ToolPreset.java` where needed; create `S/tools/impl/GetWriteReviewStatusTool.java`, `B/guides/get_write_review_status.md`. Test new `T/tools/impl/GetWriteReviewStatusToolTest.java` and relevant existing preference, protocol, preset, and write tests.

**Interfaces:**

- `PreferenceConstants.PREF_BSL_WRITE_REVIEW_ENABLED = "bslWriteReviewEnabled"`, with default `true`.
- `ConsentSettingsService.isBslWriteReviewEnabled()` reads the boolean independently of destructive consent; absent store is true. Add the normal UI setter without exposing the key to MCP settings/profile writes.
- `GetWriteReviewStatusTool.NAME = "get_write_review_status"`; input required `reviewId: string`; fixed JSON response with permissive outputSchema, no mutation annotations, and immediate lookup. Unknown/evicted/blank IDs are actionable `ToolResult.error` results.
- `WriteModuleSourceTool.getResponseType(Map<String,String> params, String result)` returns JSON for its own JSON review/error envelopes and Markdown for its historical direct result. Decide from the completed payload, never from current settings or a shared last-call field.

- [ ] Write failing tests for default-on fresh/upgrade/no-store behavior, save/default restoration, unchanged destructive consent, and read-only preset access to status. Assert that MCP settings/profile writes cannot change the preference and that extra `skipReview`/`confirm` arguments cannot bypass review. Pin review JSON versus historical direct Markdown, including toggles during one call and two concurrent callers.

  Include `missingStoreStillRequiresReview` with the existing service's store-resolution seam returning null:
  ```java
  assertTrue(ConsentSettingsService.getInstance().isBslWriteReviewEnabled());
  ```
- [ ] Run `bash source/compile.sh`; expect the new preference/routing/status assertions to fail.
- [ ] Implement the preference and status registration/guide. Assign status to the code toolset with read-only annotations; preserve its availability in both read-only and code-review presets. Add guide fields, schema, and a test per registered tool to satisfy coverage ratchets.
- [ ] Route execution using one preference snapshot: off -> old direct path; on -> validate/prepare, refuse dirty/headless/UI-unavailable, no byte change -> JSON `noChange`, otherwise register and open. Return the current snapshot (normally `awaitingApproval`) if an immediate user click already changed it. Reused pending requests return/focus the same ID; UI failure cancels admission and writes nothing. Return `reviewId`, status, identity, deadline, applied/mutation facts, and polling guidance.
- [ ] Run `bash source/compile.sh`; verify no tab, folders, model follow-up, or file write occurs before acceptance, and no future preference toggle changes a pending proposal's decision requirement.
- [ ] Review and commit `feat: require configurable BSL diff approval and expose review status`.

### Task 6: Tie review ownership to server and project lifecycle

**Files:** Modify `S/McpServer.java`, `S/Activator.java`, `S/utils/BslWriteReviews.java`, `S/ui/BslWriteReviewUi.java`; test `T/McpServerTest.java`, new `T/utils/BslWriteReviewLifecycleTest.java`, and registry tests.

**Interfaces:** `McpServer.start` begins a fresh review generation once startup is admitted; `stop` cancels pending reviews before invalidating actions. `Activator.stop` closes the registry and detaches listeners before services/UI teardown. Resource listeners revoke project tokens on close/delete/recreate; listener ownership belongs to the registry.

- [ ] Write failing tests for start/stop/restart, failed startup, shutdown versus Apply, queued application before mutation, completed mutation during shutdown, and project close/reopen at the same location. Verify an old tab cannot act on a new generation.

  Include `serverStopRevokesAwaitingProposal`:
  ```java
  reviews.cancelPending("server-stopped");
  reviews.apply(id);
  assertEquals(BslWriteReviews.State.CANCELLED, reviews.get(id).status());
  assertEquals(0, commitCalls.get());
  ```
- [ ] Run `bash source/compile.sh`; require failures at lifecycle invalidation assertions.
- [ ] Wire lifecycle cleanup idempotently. Never call SWT synchronously while holding the server monitor or a workspace lock. Make project listeners publish cancellation quickly; UI closure is asynchronous. Cancel an accepted but not-yet-mutating job on stop, with a commit gate that races atomically against entering mutation; an entered write reports its actual outcome.
- [ ] Run `bash source/compile.sh`; require no active source payloads, listeners, or scheduled expiry work after unload. Assert pending cancellation never overwrites a committed/unknown terminal outcome.
- [ ] Review and commit `fix: invalidate BSL reviews across EDT lifecycle changes`.

### Task 7: Verify the user workflow and update client guidance

**Files:** Modify `tests/e2e/tools/test_write_module_source.py`, create `tests/e2e/tools/test_get_write_review_status.py`; update `.github/workflows/e2e-tests.yml`, `B/guides/write_module_source.md`, generated `docs/tools/`, `tests/e2e/tools_list.golden.json`, README tool catalogue/counts, and relevant `agent/`/`rules/` workflows that assume writes finish synchronously. Add `tests/e2e/BSL_WRITE_REVIEW_CHECKLIST.md` for human UI scenarios.

**Interfaces:** Headless CI uses the same plugin preference via an explicit test-only customization file (`com.ditrix.edt.mcp.server/bslWriteReviewEnabled=false`) passed to its isolated Eclipse launcher. No production env bypass is added. Review-enabled tests use a separate UI workspace; no MCP helper that accepts proposals is shipped.

- [ ] Write failing e2e tests for status argument/error quality and direct opt-out writes, checking real disk effects. Add widget/live cases for default-on preparation, rejection/close/expiry, duplicate submission, new file, stale base, dirty buffers, and post-write adopted-module failure. Assert zero file/model diff before approval and exact expected changes after it; preserve existing per-test fixture reset rules.

  Include `test_write_review_does_not_write_before_acceptance`; use the harness's parsed tool result and the real fixture diff:
  ```python
  assert payload["status"] == "awaitingApproval"
  assert payload["applied"] is False
  assert payload["reviewId"]
  assert_no_diff()
  ```
- [ ] Run `python tests/e2e/run_all.py --project TestConfiguration --filter write_review` against the development EDT; expect the new cases to fail until activation/harness setup is correct. Separately run the write-module filter. Keep UI cases explicit in the checklist when they cannot run in headless CI; do not turn unperformed UI tests into passing skips.
- [ ] Update guides and agent workflows: awaiting approval is not a completed write; poll the ID and do not blindly retry append after an uncertain outcome. Generate docs with `python docs/generate_tool_docs.py --help` to obtain the supported invocation. Regenerate the golden using `EDT_MCP_UPDATE_GOLDEN=1 python tests/e2e/run_all.py --project TestConfiguration --filter test_tools_list_matches_committed_golden_snapshot`.
- [ ] Run `bash source/compile.sh`, redeploy the newly built artifact to a non-elevated development EDT copy, and execute the UI checklist plus the full e2e suite. Run protocol conformance using the repository's pinned baseline/instructions. Inspect EDT logs, including rotated logs, and classify failures; do not infer runtime correctness from compilation.
- [ ] Apply the `edt-mcp-ready-to-deploy` gate and request independent whole-branch review before claiming implementation complete. Review staged changes and commit `test: cover BSL review lifecycle and document approval workflow` only with accurate verification evidence; report unavailable live checks explicitly.

## Plan self review

- Spec coverage: Tasks 1/5 cover wire compatibility and preference; Tasks 2/6 cover bounds, expiry, deduplication, and lifecycle; Tasks 3/4 cover exact accepted bytes and UI safety; Task 7 covers the client workflow and runtime gates.
- Interface consistency: Tasks 3/5 consume the immutable type and registry signatures defined in Task 2; Task 4 calls the same apply/reject actions; Task 1's new overload is used by Task 5 without mutable response state.
- Review Focus: each of the five conditions above has explicit tests in its owning task.
- No product code, build, or runtime verification is represented as already completed by this plan.
