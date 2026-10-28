Read the status of a review returned by `write_module_source`. This tool never accepts or rejects changes and never returns module source.

## Workflow

Call with `reviewId` and wait about three seconds between polls while `status` is `awaitingApproval` or `applying`. Only a person using the EDT comparison tab can apply the proposal. There is no MCP acceptance argument or acceptance tool.

Terminal states are `applied`, `rejected`, `stale`, `expired`, `cancelled`, and `failed`. `mutationCommitted: true` means source was written even if follow-up failed. `mutationOutcomeUnknown: true` means the write has entered or returned an opaque outcome: reread source before deciding what to do. Never blindly retry an append after a connection loss or uncertain outcome.

`stale` requires reading the current module and submitting a new proposal. Closing a pending tab rejects it; the decision deadline is 30 minutes. Server/project shutdown cancels proposals that have not entered mutation. IDs do not survive plugin/EDT restart; unknown IDs and retained-status eviction are errors, not rejections.

## Example

```json
{"reviewId":"the-id-returned-by-write_module_source"}
```
