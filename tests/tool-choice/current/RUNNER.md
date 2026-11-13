# Current EDT-MCP tool benchmark runner

The catalog and requests in this directory are the complete model-visible inputs. Do not read the source checkout, reference labels, grading rules, or other agent instructions. This is a simulated tool workflow, not permission to operate a real EDT workspace.

Choose tools from `catalog.json`. Read `guides/<tool>.md` only through a recorded `get_tool_guide` call. A separate trusted host supplies scripted observations from the private dataset and records elapsed wait time. Never invent tool results, acceptance decisions, request/review IDs, or elapsed waits. The fixture's human decision is an observation, not an action the model can issue.

The external host records a run JSON object with `provider`, `model`, `model_version`, the three hashes from `identity.json`, and `answers`. Each answer contains `id`, ordered `calls`, and `final_status`. A call records `tool`, `args`, `result: {isError, data}`, and `wait_ms` since the preceding observation. Final statuses are `completed`, `needs_recovery`, `cancelled`, or `previewed`, according to the task and observed state. Tool results and timing are host-owned trace data, not model proposals. Each case starts from a fresh independent simulation; do not share pending reviews or request receipts between cases.

For a request with no supplied simulation steps, grade only tool selection and argument validity. Its host may use a generic `{isError:false,data:{success:true}}` response; this does not establish real operation success. For protocol requests, use the private dataset's scripted responses exactly. Return actual observed calls, not a proposed future plan.

Do not start a paid run automatically. The operator chooses a model/provider, budget, and sampling settings before collecting traces. Record those settings alongside the run metadata. Missing requests must remain absent, so the report marks them unrun.
