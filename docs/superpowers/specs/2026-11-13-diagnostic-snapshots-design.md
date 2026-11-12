# Diagnostic snapshots

## Intent

Show which EDT validation problems appeared, disappeared or remained after a code
change. Existing problem counts can hide two fixes accompanied by two regressions.
The user approved this functional scenario; the interface and implementation
choices below are proposed for review.

## Approaches

1. Recommended: add one `problem_snapshots` tool with `capture` and `compare`
   actions. Immutable snapshots make comparison and pagination repeatable. Reuse
   the marker-row builder and BM read boundaries; preserve the existing
   `get_project_errors` response contract.
2. Extend `get_project_errors` with capture/comparison modes. This saves one tool
   registration but mixes a Markdown listing with snapshot IDs, retention and
   structured comparison results in an already large implementation.

This is a small feature using Java 17 and existing dependencies. It adds no UI,
background service, database, configuration setting or external dependency.

## Proposed contract

- `action=capture`: require `projectName`; optionally filter by `severity`,
  `checkId` and exact `modulePaths`, using existing problem-list semantics.
  Return an opaque `snapshotId`, scope, capture time and problem count.
- `action=compare`: require `baselineId` and `snapshotId`. Compare the two frozen
  snapshots, rejecting different projects or normalized filter scopes. Return
  `counts` for `new`, `fixed` and `remaining`, plus diagnostic rows with their
  category, check, severity, message, location and available before/after lines.
  Standard `limit` (default 100, maximum 1000) and `offset` page the combined
  report in deterministic category/location order. Counts cover the full report.
- Successful results are JSON because their IDs are passed back to the tool and
  their rows contain machine-readable source positions. Errors use `ToolResult`.
- Snapshots are immutable, process-local and shared by clients of this workspace.
  Retain the last 10 successful captures. Restarting EDT or evicting a snapshot
  invalidates its ID; errors ask the caller to capture again.
- Cap each snapshot at 10,000 matching problems. Refuse overflow instead of
  comparing truncated lists or evicting a previous snapshot for a failed capture.
- Read the saved EDT validation markers inside existing model read boundaries.
  Capturing does not run validation or save editors. The workflow must complete
  revalidation before each capture when fresh results are required. Capture time
  is observation time, not proof of when EDT last validated every object.

## Matching and limits

Compare multisets, preserving repeated occurrences. The matching signature uses
project, module path (or resolved metadata location for a non-module problem),
check identity/contributor, severity and exact message. Line, offset and marker
creation time are not identity: revalidation recreates markers and edits shift
lines. For each signature, the shared count is `remaining`; an increased count
is `new`, and a decreased count is `fixed`.

This is signature/count comparison, not proof that an individual occurrence was
fixed. If one occurrence disappears and an indistinguishable occurrence appears
elsewhere in the same location, the count remains unchanged. State this limit in
the tool guide and result matching metadata. A message/severity change appears
as one fixed and one new problem. File or object renames likewise change scope
identity. Never silently drop unreadable markers or treat an unreadable project
as an empty clean snapshot; refuse an incomplete capture with a recovery hint.

## Workflow

1. Complete EDT validation and capture the baseline.
2. Change code, complete validation and capture the resulting diagnostics.
3. Compare the returned IDs; inspect new problems and fix them.

No automatic source edits, quick fixes, persistent history, Git comparison,
automatic validation or tracking of runtime errors are included.

## Acceptance and verification

- Equal totals with different signatures report both fixed and new problems.
- Shifting lines alone leaves matching diagnostics remaining.
- Repeated identical problems preserve their counts; messages, severities,
  projects and module paths cannot collide through string concatenation.
- Comparison rejects incompatible scopes, missing/evicted IDs, unreadable or
  closed projects, and capture overflow. Pagination never truncates counts.
- Reuse shared project resolution, marker interpretation and transaction helpers.
- Add unit coverage for comparison/retention and the tool contract, plus live
  end-to-end capture/edit/revalidate/capture/compare with fixture restoration.
- Register the read-only tool in its group/toolset, update its guide, generated
  docs, README and live tools-list golden. Run the project's build/unit, live
  e2e and protocol gates before merging the local working branch into `master`.
