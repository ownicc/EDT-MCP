Compare saved EDT validation diagnostics before and after an edit. Captures are complete,
immutable observations of the stored marker set. No source, model or settings are changed.

## Workflow

1. Complete validation (use `revalidate_objects` after saved edits) and wait until
   `list_projects` reports the project ready.
2. Capture the baseline: `{action:'capture', projectName:'MyProject'}`. Keep its `snapshotId`.
3. Change code, complete validation, then capture again with exactly the same filters.
4. Compare: `{action:'compare', baselineId:'<before-id>', snapshotId:'<after-id>'}`.

## Parameter details

- `action` is required: `capture` or `compare`.
- Capture requires `projectName`. Optional `severity` uses the same EDT severity names
  as `get_project_errors`; omit it for all severities. `checkId` matches a case-insensitive
  substring of the symbolic check id or short UID. `modulePaths` restricts the capture
  to existing source-relative BSL modules, case-insensitively, accepting `src/` prefixes
  and backslashes. Omit it for all diagnostics, including metadata-only problems.
- Compare requires `baselineId` and `snapshotId` from successful captures. The project
  must still exist and be open. Both captures must have the same normalized project,
  severity, check and module filters. Filter ordering, duplicate paths and path case do
  not change scope. Filters cannot be changed by a compare call.
- Compare-only `limit` defaults to 100 and is clamped to 1-1000. `offset` defaults to 0
  and must be a nonnegative integer. Rows are ordered new, fixed, remaining, then by
  location/check/message and saved coordinates. Counts always describe the complete
  comparison. Follow `hasMore` by adding `returned` to `offset`; immutable input
  snapshots make pagination repeatable while both IDs remain retained.

## Results

Capture returns `snapshotId`, `scope`, `capturedAt` and `problemCount`. Observation
time is not the validation time: captures do not validate, save editors or guarantee
that every stored marker was recomputed. Unsaved source changes are not analyzed.

Compare returns `baselineId`, `snapshotId`, `scope`, full `counts` (`new`, `fixed`,
`remaining`), `total`, `returned`, `limit`, `offset`, `hasMore` and `problems`.
Each row has `status`, `checkId`, `severity`, `message`, `location` and, when known,
`modulePath`, `contributorId`, `beforeLine`/`afterLine`, `beforeOffset`/`afterOffset`
and `beforeLength`/`afterLength`. Lines are 1-based; offsets/lengths are stored UTF-16
coordinates. A new row has only after coordinates; a fixed row has only before
coordinates. Remaining rows show both observed positions, without claiming a unique
individual match between identical occurrences.

## Matching limits

`matchingBasis='signatureCounts'`: match the multiset of module (or metadata location),
check/contributor, severity and exact message within one project. Line, offset and
marker creation time are excluded, so inserting lines does not produce false changes.
Repeated identical problems retain their counts: three before, two after means one
fixed and two remaining. Fixing one occurrence and adding an indistinguishable one
elsewhere in the same module cannot be distinguished. `matchingLimit` repeats this
limit in the result. Changed messages, severity, paths or metadata locations appear as
fixed plus new; these categories describe observed signatures, not proof of root cause.

## Retention and refusals

Only the last 10 successful captures are kept in this EDT process, shared by its
clients. Restarting EDT or capturing an eleventh snapshot evicts old IDs; capture
again when an ID is unknown. Snapshots are never persisted to disk.

Each capture allows at most 10000 matching problems. Overflow refuses the whole
capture and suggests narrower filters; failed captures never evict an older snapshot.
Unavailable/closed projects, active builds, unreadable marker identities/locations or
missing services refuse rather than storing an incomplete result as clean.
There is no automatic fix or undo: this tool only reads diagnostic values.
