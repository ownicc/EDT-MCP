# Project readiness implementation plan

**Goal:** Extend `get_server_status` with an explicit, bounded explanation of project prerequisites, preserving its parameterless server snapshot.

**Design:** Optional `projectName` requests a project probe. Optional `operation` selects `metadata` (default), `diagnostics`, or `runtime`. These are prerequisite profiles, not complete tool preflights. Reuse `ProjectContext`, `ProjectStateChecker`, and `BmModelResolver`; diagnostics additionally needs marker/check services. Runtime additionally checks the registered local platform installations using the named EDT installation manager, without resolving, searching, refreshing, downloading, or launching a runtime. Availability does not establish application binding, component/version compatibility, credentials, licensing, or database readiness.

**Approaches:** Extending the existing status tool avoids another discovery surface and keeps its default cost unchanged. A new tool would isolate the interface but duplicate status discovery. A universal tool dependency registry would cover more operations but duplicate existing guards and exceed the approved first stage. Use the existing tool with three explicit profiles.

**Architecture:** A focused `ProjectReadiness` helper owns observation, classification, recovery advice, and a two-second caller deadline. A shared admission flag allows only one daemon probe and queues none, bounding abandoned work even if EDT ignores interruption. The tool owns argument validation and adds only an optional `readiness` object to its existing snapshot.

**Constraints:** Read only; no editor saves, project builds, database connections or updates, platform installation, preference writes, secret/path serialization, or UI dispatch. Explain `ready`, `building`, `missing`, `unsupported`, and `unavailable`. Unknown facts fail closed. Preserve server fields and calls without parameters. Work on a local branch; merge verified changes into local master, no PR or work-branch push.

## Implementation

- [x] Add failing tool contract tests for optional parameters, invalid combinations, and unchanged parameterless snapshots.
- [x] Add the helper and tests for project lifecycle, model registration, required services, operation-specific runtime, bounded timeout/busy/interruption, and redaction.
- [x] Integrate the helper, update the shipped guide and generated tool documentation, and add read-only wire checks.
- [x] Run the focused Java tests, the reactor suite/build, relevant Python checks and documentation generation checks. Review the complete diff before local merge.

## Review focus

- A missing runtime must not block metadata or diagnostics.
- An EDT context without a registered BM model must not appear ready.
- A timed-out probe must not spawn unlimited threads on repeated requests.
- Platform failures must not serialize paths, credentials, or arbitrary exception messages.
- `ready` describes observed project prerequisites, not success of a later launch or mutation.

## Execution record

- Scope approved in conversation: small first stage, existing checks, bounded on-demand diagnostics, backward-compatible default call.
- Ruling: use three prerequisite profiles instead of pretending to validate every parameter of every tool. The runtime profile observes local registered installations only; remote execution environments are outside this first stage.
- Initial contract tests failed on three expected missing-feature assertions. The helper's first compile failed because its class did not yet exist; its first executable run passed all eight cases. The first integration run exposed a test assumption: JsonSchemaBuilder emits an empty required array instead of omitting it. Corrected the assertion to check that neither parameter is mandatory.
- Moved work into `/tmp/edt-readiness-worktree` after unrelated edits appeared in the original checkout. Those edits were left intact.
- Focused Maven verification passed 29 tests. Python runner tests passed 319 tests; documentation generator tests passed two tests.
- An initial live host assembled from Tycho's unit-test bundles could not initialize its project context because EDT services were unavailable. The diagnostic reported `context_not_started` rather than claiming readiness.
- A second isolated host used the installed EDT bundles, a separate configuration/workspace, the built plugin, and a copied TestConfiguration. All three new wire-test functions passed over MCP IPC; metadata, diagnostics, and runtime profiles returned ready. Fixture file hashes were identical before and after the probes. The generated tool page was assembled from that build's live `resources/read` guide using the existing generator's `assemble_page` function.
- Every EDT launch used `GTK_THEME=Adwaita:light` and `__EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/10_nvidia.json`. Both task-owned hosts were stopped; the user's EDT was not restarted or modified.
- Independent read-only review found no actionable issues, including a second pass over the final E2E tests and generated documentation.
- Full Maven `verify` passed: 13,904 tests, zero failures/errors, 31 skipped; BUILD SUCCESS. The documentation ratchet passed all five tests. Original master independently advanced to `5abf94a8`; include that disjoint helper cleanup and run focused combined verification before merging.
- Combined verification on top of `5abf94a8` passed for GetServerStatusToolTest, ProjectReadinessTest, ToolDocsRatchetTest, and BmObjectAddressTest, with BUILD SUCCESS. `git diff --check` passed. Ready for local fast-forward into master; no push or user-EDT installation is part of this change.
