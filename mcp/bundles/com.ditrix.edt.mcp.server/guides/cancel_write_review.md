Withdraw an awaiting BSL review returned by `write_module_source`. This tool never applies changes, saves a document, or writes source.

## Workflow

Pass the `reviewId` returned by `write_module_source`. The ID is the capability for that review in the current workspace service; retain it until the decision completes. An awaiting proposal becomes `cancelled`, with `mutationCommitted: false`, a completion time and reason `client-withdrawn`. The comparison tab updates its status and disables Apply and Reject; its module and retained proposal payload are released for a new review.

Repeating the call returns the same terminal snapshot. If Apply has already won, the response reports `applying` or its eventual terminal outcome. Cancellation does not interrupt an accepted write or relabel its result. Poll `get_write_review_status` while it is applying. `mutationOutcomeUnknown: true` requires rereading source before another write.

Other terminal states, including `applied`, `rejected`, `stale`, `expired`, and `failed`, are returned unchanged. A proposal whose approval deadline has passed is reported as `expired`. An unknown or evicted ID is an error; reread the module before submitting another write. In-memory review IDs may be lost on restart.

Withdrawal is irreversible for that proposal. To propose changes again, reread the module and call `write_module_source` with the current source/hash. Only a human can accept a new review in EDT. Read-only presets exclude this tool and retain status polling.

## Example

```json
{"reviewId":"the-id-returned-by-write_module_source"}
```
