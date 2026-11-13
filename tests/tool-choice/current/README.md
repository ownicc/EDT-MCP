# Current-catalog tool-choice benchmark

This is a separate benchmark from the historical experiment in the parent directory. Its catalog comes from the plugin's generated `bootstrap-catalog.json`, built through the same `McpProtocolHandler.toolCatalog` path as live dispatch. `manifest.json` pins the source commit, plugin version, and canonical SHA-256 of the catalog and dataset. The snapshot contains 112 registered tools, including `ask_workmate` and `git`, which the default live golden omits.

There are **31 authored requests covering 28 of 112 tools**. All 24 tool names added after the historical contract are included. The other 84 tools have explicit exclusions in `cases.json`; their historical results do not count as measurements of this catalog. This small, deliberately selected sample does not establish whole-catalog or real-user accuracy. The complete registered catalog is a windowed profile without preset, progressive-disclosure, or headless filtering; it is not every client's default catalog.

Six requests grade simulated execution protocols: a destructive preview followed by confirmation of the same effect; a pure BSL preview; awaiting human review with paced polling until applied; reconciliation after an uncertain append; stale-review recovery with a fresh hash and request ID; and cancelling an awaiting review. Other requests measure schema-valid tool selection and do not grade a claimed final operation state. Argument checks use JSON Schema plus selected implementation-level selector/hash rules; they do not emulate every EDT business invariant. Fixture data is a normalized, scripted projection of observations, not a full live wire response. A transport failure is a host observation with unknown mutation outcome, not an invented server acknowledgement.

## Run without a model or paid API

Requires Python 3.10 or newer. Use a virtual environment; the historical benchmark keeps its existing dependencies and input files.

```bash
python3 -m venv /tmp/edt-tool-benchmark-venv
/tmp/edt-tool-benchmark-venv/bin/pip install -r tests/tool-choice/current/requirements.txt
/tmp/edt-tool-benchmark-venv/bin/python -m unittest discover -s tests/tool-choice/current -p 'test_*.py'
/tmp/edt-tool-benchmark-venv/bin/python tests/tool-choice/current/benchmark.py audit
```

`audit` starts neither EDT nor a model. It verifies pinned hashes, historical-input hashes, the current live-golden subset, and the coverage/exclusion ledger, then reports all requests as **unrun** with `model:null` and `pass_rate_answered:null`. `audit-report.json` is that unmeasured baseline, not a model score. A drifted live golden fails visibly instead of silently grading the new API against the old snapshot.

To verify against a freshly built plugin, add:

```bash
/tmp/edt-tool-benchmark-venv/bin/python tests/tool-choice/current/benchmark.py audit \
  --plugin mcp/bundles/com.ditrix.edt.mcp.server/target/com.ditrix.edt.mcp.server-1.0.0-SNAPSHOT.jar
```

## Collect and grade a model run explicitly

```bash
/tmp/edt-tool-benchmark-venv/bin/python tests/tool-choice/current/benchmark.py stage \
  --stage /tmp/edt-current-model-inputs
# Choose a provider/model/budget explicitly. Start the external runner outside this checkout.
# Its host uses cases.json for scripted observations; the model sees only staged public inputs.
# Save the host-recorded trace to /tmp/edt-current-answers.json, then:
/tmp/edt-tool-benchmark-venv/bin/python tests/tool-choice/current/benchmark.py grade \
  --answers /tmp/edt-current-answers.json --report /tmp/edt-current-result.json
```

The destination must be new and outside the checkout. Staging copies catalog descriptors, requests, rendered guides, hash identity, and `RUNNER.md`. It excludes expected tools, reference steps/results, final-status labels, repository instructions, historical answers, and grading code. Guide reads must be recorded as tool calls. The external host, not the model, supplies fixture observations and records waits. This CLI intentionally provides no provider adapter and performs no implicit paid run.

The run object records `provider`, `model`, `model_version`, `catalog_sha256`, `dataset_sha256`, `guides_sha256`, and an `answers` array. Record sampling settings and the runner version alongside them. Each answer records `id`, `calls: [{tool,args,result:{isError,data},wait_ms}]`, and `final_status`. `RUNNER.md` defines the protocol. Missing questions stay unrun; duplicate/unknown IDs, missing model provenance, or mismatched hashes are refused. A partial run reports its answered denominator and every unrun ID. Grade exits nonzero when an answered case fails.

The host must not put private fixture steps, labels, argument contracts, or reference responses in the model's initial prompt. Deliver observations only after their corresponding call. Public request IDs are opaque. Private argument-contract JSON Schemas enforce the requested operation (for example list instead of terminate), named targets and required selectors. Protocol steps marked `fresh_request_id` accept any nonblank ID up to 128 characters that differs from earlier request IDs; there is no hidden required replacement value. Deliver the fixture's returned review ID after that call. Generic tool-choice responses are not evidence that an operation actually executed. Fixture self-tests validate the grader; they are never published as model measurements. Human approval is a supplied observation; the model cannot grant it or invent an applied outcome.

## Refresh after an intentional catalog change

Build the plugin normally, then export from its generated bootstrap artifact:

```bash
/tmp/edt-tool-benchmark-venv/bin/python tests/tool-choice/current/benchmark.py export \
  --plugin mcp/bundles/com.ditrix.edt.mcp.server/target/com.ditrix.edt.mcp.server-1.0.0-SNAPSHOT.jar \
  --catalog /tmp/edt-new-catalog.json
```

Review the descriptor diff before replacing `current/catalog.json`. Classify every added/removed tool in the dataset or exclusion ledger. Repin the source commit/version and catalog/dataset SHA-256 and guide_digest() in `manifest.json` using `digest()`; rerun audit and regenerate its unrun report. Keep prior model runs with their original hashes. Do not copy new descriptors into the historical baseline, contract, answers, or result tables.

When another feature changes the live golden, audit deliberately requires this refresh before it can claim the catalog is current. Coordinate that refresh after the feature batch is merged; never erase an old run's provenance to make a new catalog appear measured.
