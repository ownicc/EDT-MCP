# Batch method replacement verification

Beads: `edt-nyw`. Worktrunk branch: `feat/bsl-batch-edits`, base `b5e4a3dd`.

`write_module_source(edits=[{methodName,source},...],expectedHash=...)` replaces several methods of one module with one proposal. `edits` implies `mode=replaceMethods`, which may also be explicit. The array has 1–50 distinct case-insensitive targets; combined incoming source is limited to 500000 characters. Each source contains one complete method with its desired documentation/annotations, same name and kind. It cannot be mixed with top-level single-edit source/anchor/fragment/overwrite fields. Inserts, deletes, renames and batches across modules are outside this change.

Every entry is validated against the original text; ranges are composed backwards in memory. The final module is syntax-checked once. Invalid entries leave no partial proposal or source mutation. The existing whole-module hash, late workspace compare/write rule, review commit and human-only Apply stay in place. BOM, delimiters and untouched text are preserved; normalization touches incoming fragments only. Receipt digests include every ordered entry and leave legacy digests unchanged. The compact tools/list schema keeps the edits description so its hash/mode/field-conflict rules remain visible.

## Evidence

| Check | Result |
| --- | --- |
| Focused baseline writer/receipt/review suite | 197 tests passed. |
| Initial new batch tests | RED: nine failures, zero errors, because edits was unsupported. |
| Focused batch and existing regressions | 207 tests passed; receipt coverage subsequently strengthened for every entry and order before the canonical run. |
| Full live write-tool family | 48 passed, one explicit review-mode skip; fixtures clean. |
| Actual pending batch on a separate review-enabled Xvfb EDT launch | One test passed, none skipped; fixtures clean. Three procedures yielded one reviewId; replay kept the ID, competing proposal refused, cancellation updated the receipt and wrote nothing. |
| Live generated docs/golden | 105 tool docs generated; four golden checks passed, two unrelated environment skips. |
| Fresh independent whole-branch review | No actionable correctness findings; reviewer changed no files. |
| Full canonical unit run | 12119 tests; one schema-compactor contract failure, zero errors, eight skipped. The one failure was fixed by keeping the edits cross-field description; targeted risk/writer checks passed. Final integration checks below follow. |
| Final canonical packaging and agent validator | Pending. |
| Final ZIP live checks and MCP conformance | Pending. |

The live direct-write checks assert all three resulting method bodies on disk, retained untouched layout, same-receipt replay with an old hash, changed-entry conflict, and unchanged source after a bad later entry, duplicate/non-object member or stale hash. Pending-review checks use the production tool, registry and actual EDT compare editor; they do not apply through MCP. Existing review/commit tests cover human acceptance and guarded commit; the new batch uses that unchanged commit path.

## Limits and choices

Verification uses copied EDT 2026.2.2.21 in Xvfb and separate disposable workspace/port 18775. Automated GUI work never uses the user's desktop. No new EDT/platform API signature or production dependency is introduced.

The whole-repository e2e suite has known baseline screenshot/page-restoration and installed-platform assumptions (`edt-11z`, `edt-cya`), reproduced before this change. This task runs the entire affected writer family, both direct/review modes, the canonical full unit suite and MCP conformance; it does not claim a new green run of every unrelated e2e tool.

Builder choices recorded in the design: replacement-only first version, explicit/inferred mode name, 50-entry cap and inherited combined source budget. General mixed-operation batches require a separate use case. Existing method targeting includes contiguous documentation/annotations; callers must include what they want to retain on each replaced method.

Session artifacts: `/tmp/edt-batch-baseline.log`, `edt-batch-red.log`, `edt-batch-regression.log`, `edt-batch-write-e2e.log`, `edt-batch-pending-review.log`, `edt-batch-golden.log`, and corresponding JUnit XML under `/tmp`. Disposable runtime jars are frozen before startup; canonical artifact checks use the exact plugin from the generated ZIP.
