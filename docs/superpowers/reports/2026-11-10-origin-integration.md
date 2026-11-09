# Integration of origin/master, 2026-11-10

Merged local `89b25ab2` (59 local commits) with origin `fc31d2e6` (49 origin commits), preserving both histories and both tool catalogues.

## Merge decisions

- Keep origin preference migration epochs 18–22; assign the unpublished fork additions `open_in_editor` and `cancel_write_review` to epochs 23/24. Existing origin v22 read-only profiles acquire the fork restrictions once. Earlier local read-only profiles may need to opt these two tools back in once.
- Identify old fork epoch18/19 read-only stores from their **preexisting** fork-only disabled tool names, before adding new names, and reconcile skipped origin restrictions (`infobase_extensions`, plus `open_editor` for Analysis Only). Origin stores without these markers retain their earlier explicit opt-ins. If both fork tools were explicitly enabled in an old local store, its origin is indistinguishable from an origin store; preserve the ambiguous choices. Reapplying the read-only preset restores the complete current restrictions in that rare case.
- Preserve origin headless direct writes without SWT review; retain the stored windowed preference and default-on human review in windowed EDT. Classify the fork editor tool as windowed-only and review status/cancellation as available without the workbench.
- Preserve native BSL diff behavior, document/disk token separation, review lifecycle cancellation, both editor tools, fork update URLs, and the union of resource keys and dependencies.
- Keep captured origin formatter pins intact. The contract verifier explicitly accounts for fork `view: disk` metadata and optional `dirty: false`; wrong views, dirty fixtures, line endings, content and trailing newline changes still fail.

## Verification

- Full Maven `verify -Dtycho.localArtifacts=ignore` on JDK25: **13,230 tests, 0 failures, 0 errors, 20 skips; BUILD SUCCESS**. Production and tests were rebuilt from a clean compilation before final fixture corrections; the final full run rebuilt the changed fixtures. Full run took 9m43s.
- Python E2E runner: **315 tests passed**; workflow script tests: **5 passed**; docs generator checks: **5 + 2 passed**.
- Migration/host checks were verified RED→GREEN, including fork18/19 collision recovery, origin22 upgrade, owner re-enable/idempotence, and headless review policy.
- Complete catalogue: **110 tools**. The **108-tool** golden snapshot (excluding `git` and `ask_workmate`) exactly matches the real build-time protocol catalogue. All 110 generated tool pages match `GuideRenderer`.
- Independent integration review found the old fork migration collision and stale exact-set assertions; corrected with regression tests. Final full verification also exposed seven stale test fixtures for completed-result response typing and insertion spacing; corrected, with all seven passing in the final full run. No deferred minor findings or declined-to-judge items.
- `git diff --check` passed. Main checkout user changes to `.gitignore` and untracked `AGENTS.md` were preserved outside the integration worktree.

Detailed local evidence: `/tmp/edt-origin-integration-20261110/` (plan, ledger, review package, RED/GREEN logs, `merged-verify.log`, and Python verification logs).
