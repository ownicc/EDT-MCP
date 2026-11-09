# BSL MCP workflow verification

Beads epic: `edt-0ks`, children `.1`–`.4`. Implementation commit: `647b167c`.
Base: `3dd56a4d`; local Worktrunk branch: `feat/effector-mcp-workflow`.

## Delivered behavior

| Tool | Change |
| --- | --- |
| `read_module_source` | Explicit `view=disk\|document`; document text/dirty state from an existing buffer, without opening or saving. Document reads have a separate `document:` hash and no disk `contentHash`. |
| `cancel_write_review` | Withdraws an awaiting proposal atomically; applying/terminal proposals retain their actual status. UI listeners receive the update. |
| `open_in_editor` | Existing path addressing or metadata object/module/form/command selectors through the writer's shared resolver. |
| `write_module_source` | Optional `requestId`, durable admission and bounded receipt replay; argument conflicts refuse, interrupted recovery reports unknown without retrying the write. |
| `get_write_review_status` | Lookup by exactly one of `reviewId` or `requestId`, including persisted recovery receipts. |

Human Apply/Reject and existing disk write guards remain. No entire-module input is required for `replaceMethod`: the caller supplies one complete method, and the server composes the proposed module and its comparison. Multiple method edits in one request are outside this epic.

Receipts are workspace-local, retained for seven days with a 1,000-record limit. They do not persist source arguments. This is bounded replay/recovery, not an exactly-once guarantee after arbitrary storage failure or retention expiry. Request IDs must not be recycled.

## Verification evidence

| Check | Observed result |
| --- | --- |
| Initial canonical `source/compile.sh` with JDK 25/Maven 3.9.11 | BUILD SUCCESS; 12,090 tests, zero failures/errors, eight skipped. |
| Agent pack validator | Passed: 13 skills, 105 registered/documented tools plus one proxy. |
| Actual Xvfb workbench profile | Three executed tests; zero failures/errors/skips. |
| Live first-three-feature checks | 20/20 passed, fixture clean. |
| Live write-family e2e | 45/45 passed, fixture clean. |
| Additional successful append/replay case | Passed; stale original hash replay returns the same receipt and source marker exists exactly once. |
| Real EDT process restart | Final canonical bundle replay/status matched the original receipt; disk unchanged and fixtures restored clean. |
| Live tools golden/schema orchestrator | Four passed, two environment skips, fixture clean. |
| Official MCP conformance on final canonical bundle | Baseline passed; no unexpected failures for protocol version `2025-11-25`. |
| Independent whole-branch review | No actionable correctness findings, including the late document-view footer guard. |
| Full e2e shards | 1,632 passed, 50 skipped, 13 failed; all four fixtures clean. All failures classified below; no new feature regression found. |

The workbench tests assert that their advisor/fixture actually ran. The old harness could exit successfully without running the GUI assertions. Its test profile now supplies EventAdmin and uses a test-only plain text editor/provider; this exercises real workbench/file-buffer behavior without claiming to certify every native BSL editor provider.

Document view deliberately omits the saved semantic-model interception footer: that model cannot describe unsaved text. Disk view retains it.

## Environment and limits

Pre-integration live tests used copied EDT **2026.2.2.21**, separate workspaces and Xvfb, with canonical bundle `1.0.0.202611091838`. JDK: Axiom 25.0.4.1; source compatibility remains Java 17. No other EDT installation was available for live compatibility checks.

The first full-suite attempt encountered a CI-only no-platform assumption: this host has a 1C platform installed, so the negative `create_infobase` probe created a test infobase. The exact new test application was dissociated/deregistered and its two new test directories removed. A subsequent attempt to mask the platform with bubblewrap failed at EDT startup inside native WebKit. Neither attempt is counted as a passing run.

The final full-suite invocation uses unchanged committed tests through a temporary host adapter that reports exactly `create_infobase::test_no_platform_runtime_errors_actionably` as an explicit environment skip. Its no-platform prerequisite is unavailable here; live-infobase tests remain disabled. All other assertions and the normal fixture/model/log cleanup remain active. A second no-platform test, `import_project_from_file::test_without_a_platform_the_call_is_refused_at_once`, also fails on this host; its identical failure was reproduced with an exact build of baseline `3dd56a4d`. Both environment assumptions are tracked in Beads `edt-cya`.

Automatic GUI work never used the user's desktop. Only disposable stand preferences disabled write review for unattended direct-write e2e; actual workbench tests separately exercised review cancellation. No push, release or PR is part of this epic.

## Integration with concurrent master changes

During full e2e, the independent BSL diff work advanced master to `6a2d4531`. The initial fast-forward refused; merging master into this branch produced `9123300b` without conflicts. The two changesets have no overlapping file paths. No handwritten merge resolution or further feature changes were needed.

The merged tree then passed a fresh canonical build: **12,109 tests, zero failures/errors, eight skipped**. All **three Xvfb workbench tests passed, none skipped**. The exact plugin extracted from the resulting `MCP-EDT.vdev.zip` (`1.0.0.202611091920`) passed **four live requestId checks**, with clean fixtures, and the official MCP conformance baseline again passed. Build, UI and live/conformance processes all exited zero.

The long four-shard e2e results above belong to feature commit `647b167c`, before this merge; they are not presented as a second full e2e run of the merged tree. The fresh full build and targeted UI/live checks cover integration with the incoming diff changes. Logs: `/tmp/edt-effector-merged-canonical-build.log`, `/tmp/edt-effector-merged-ui.log`, `/tmp/edt-effector-merged-smoke.log`, `/tmp/edt-effector-merged-conformance.log`.

## Existing failures outside the epic

The four shards scheduled 1,695 results from 1,689 selected tests: end-of-run log ratchets intentionally run in every shard. All four log ratchets passed, and all fixture trees were clean. The 13 failures comprise 12 screenshot opt-in refusals and the one no-platform import assumption.

The disposable full-suite stands initially omitted the explicit screenshot opt-in used by CI. All 12 screenshot cases therefore refused at the disabled-tool gate. Re-running that whole family with CI Tools preferences produced **11 passed, one error, one skipped, fixture clean**. The remaining error is `test_show_element_switches_the_page_and_puts_the_editor_back`: the following active-editor capture still equals `PageExtra`.

An exact build of baseline master `3dd56a4d`, on a fresh isolated EDT workspace, reproduced the **same** screenshot error and the same 11/1/1 result, as well as the no-platform import failure. These are not new regressions of this branch. Screenshot restoration needs separate investigation (`edt-11z`); the e2e environment gates are tracked in `edt-cya`. The report does not claim the full e2e suite is green. The canonical full build and feature acceptance checks pass; baseline-only issues are tracked separately and do not expand this epic. No production/test-assertion changes were made to conceal them.

## Reproduction

Build with the installed toolchain:

```sh
bash source/compile.sh \
  --java-home /opt/1C/1CE/components/axiom-jdk-full-25.0.4+5-x86_64 \
  --maven-home /tmp/edt-comparison-tools/apache-maven-3.9.11
```

Use `-Pworkbench-tests` under Xvfb with `JAVA_TOOL_OPTIONS=-Dedt.mcp.uiTests=true` for `ReadModuleDocumentWorkbenchTest,OpenInEditorWorkbenchTest,BslWriteReviewCancelWorkbenchTest`. Run full e2e as four `tests/e2e/run_all.py --shard I/4` invocations, each with its own checkout, EDT workspace and MCP port. A no-platform CI host does not need the host-specific adapter.

Session logs are under `/tmp/edt-effector-*`; full-suite shard logs/XML are under `/tmp/edt-effector-shards-ci/{1,2,3,4}/`. These are local verification artifacts, not shipped plugin files.
