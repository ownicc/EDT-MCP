# Current tool-choice benchmark plan

**Goal:** Add a separate, reproducible current-catalog benchmark without replacing the historical experiment or implicitly starting a paid model run.

**Design:** A standalone Python CLI extracts the complete wire catalog from the existing build-generated `bootstrap-catalog.json`, pins its canonical SHA-256 and source commit, and stages catalog/request/guide inputs outside the checkout. A focused authored dataset covers the 24 post-historical capabilities plus safety protocols and project readiness. Every other tool has an explicit exclusion reason; coverage is never presented as complete. The evaluator validates argument JSON Schemas with jsonschema and checks simulated call/result traces, preview/confirm identity, human-review polling, cancellation, stale recovery, and safe retry after uncertain outcomes. It never calls EDT or a model.

**Alternatives:** Extending the historical dataset would mix the old 87-tool experiment with the current API. A new network model runner would add provider/cost policy unnecessarily. Use separate fixtures and an offline trace grader; collect model traces through an explicitly chosen external runner later.

**Constraints:** Preserve historical baseline/contract/questions/answers/results. Record missing answers, model/provider provenance, catalog/dataset hashes, exclusions, and simulation limits. Authored observations belong to the fixture, never model authority. No paid calls or CI dispatch. Do not edit another session's edt-8qy.5 work.

## Tasks

- [x] Test catalog/hash pinning, schema/type/enum/nested constraints, coverage/exclusion accounting, and unrun reports.
- [x] Implement `tests/tool-choice/current/benchmark.py`, fixtures, dependency declaration, and runner instructions. Stage only public inputs; never ship expected tools, protocol labels, or reference answers to a runner.
- [x] Cover preview/confirm, asynchronous review waiting, cancellation, and reconciliation before an unsafe retry with adversarial traces.
- [x] Run offline unit and CLI checks, verify the historical inputs stay byte-identical, get independent review, and prepare the verified change for local merge.

## Evidence and rulings

- agents-best-practices 1.18.0, canonical commit `da245f8cbfc4c358e8c3cde61dbb703bd40292bc`; used its trace-grading and denominator/provenance guidance. Skill installation was not overwritten.
- Use the existing bootstrap catalog generator: it shares `McpProtocolHandler.toolCatalog` with live dispatch. Current build has 112 registered tools; the 110-tool live golden excludes `ask_workmate` and `git`. Pin and expose that difference instead of silently dropping tools.
- The get_server_status entry in the live golden predates edt-8qy.10. Regenerate that entry from the real shared schema builder; the remaining 109 entries match the build. This fixes a concrete pending golden mismatch without changing server behavior.
- jsonschema 4.26.0 supplies complete JSON Schema validation; no custom partial validator and no provider SDK. Install only in a task-owned temporary virtual environment for verification.
- Final sample: 31 requests, 28 covered tools, 84 explicit exclusions, six simulated protocol cases. The stored report has zero answered questions, no model/provider, and no success percentage. No model or paid API was run.
- Independent review found and resolved label leakage through public IDs, incorrect editor labels, a past-tense recovery/replay contradiction, a hidden fixed replacement request ID, and unsafe action acceptance. Public IDs are opaque; requested actions use private argument-contract schemas; fresh request IDs are value-independent. A second review found no remaining actionable issues in those fixes.
- Verification: 16 new offline unit tests passed; 319 existing E2E runner tests passed; 21 GetServerStatusTool Java tests and Maven verify passed while generating the real catalog artifact. CLI export matched the pinned 112-tool catalog; audit matched the live-golden subset; staging kept labels/contracts/steps private; partial grading preserved 30 unrun requests; wrong intent returned exit 1; historical overwrite returned exit 2. All 76 historical tracked files except the README pointer remained byte-identical to the base. py_compile and git diff --check passed.
- The unknown-write request explicitly forbids resending source after the connection failure. It measures receipt-only lookup and reconciliation, rather than rejecting a generally safe same-ID replay without a stated task constraint.
- Subsequent catalog changes, including another session's verification_report, require exporting and repinning a new catalog/dataset or exclusion ledger before a collective current-catalog audit. Keep old run hashes; never relabel an older run as a new-catalog measurement.
