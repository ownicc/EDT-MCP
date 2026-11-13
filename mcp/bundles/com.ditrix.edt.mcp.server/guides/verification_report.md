## Workflow

1. Finish the intended baseline validation with `revalidate_objects`, then call
   `problem_snapshots(action='capture', projectName=..., modulePaths=[...])`
   **before editing**. Save its `snapshotId`. Omit filters to cover the project.
2. Make the authorized change and finish its validation with existing tools.
3. Run only the tests explicitly selected for the task, separately from this report.
   Save complete JUnit XML on the machine running EDT. `run_yaxunit_tests` can
   update an infobase by default; this report never invokes it.
4. Call `verification_report(baselineId=..., testReports=[absolute paths])`.
   Omit `testReports` when no tests were selected. The report observes existing
   metadata validation within `waitSeconds`, captures the baseline's exact scope,
   and combines diagnostic differences with selected test evidence.

```json
{"baselineId":"<pre-edit snapshotId>","waitSeconds":5,"limit":100}
```

For test evidence, add `"testReports":["/absolute/server/path/junit.xml"]`
(use an absolute Windows path when EDT runs on Windows). Maximum ten reports,
2 MiB per file. Aliases of the same real file are rejected as incomplete evidence.
JUnit `testsuite`/`testsuites` nesting is supported. Declared counts, when present,
must agree with actual testcase outcomes; partial or empty reports cannot pass.
GoogleTest disabled cases count as skipped. Multiple failed assertions in one
testcase count as one failed test, with their messages retained.

## Reading the result

`success:true` means an evidence report was assembled. Read `verdict`:

- `regression`: at least one new diagnostic occurrence or an observed failed/error test.
- `incomplete`: validation is pending/unobservable, capture failed, selected test
  evidence is missing/invalid/stale, or some selected tests were skipped.
- `noNewProblems`: no new diagnostics and no failed or incomplete selected tests.
  Existing problems can remain. This is not an error-free-project claim.

The report includes `scope`, `baselineCapturedAt`, `capturedAt` when captured,
`observedAt`, `validation`, `diagnostics`, `tests`, `summary` and `limitations`.
An unconfirmed capture has no invented diagnostic totals. `tests.state=notSelected`
means test results were not checked, even when the diagnostic verdict is favorable.
A failed test keeps `regression` even when another report is missing; inspect
`tests.complete` and individual report states for the incomplete evidence too.

Diagnostic counts use the same signature/count matching as `problem_snapshots`.
Replacing a problem with another signature is detected even when total count
stays equal. Indistinguishable occurrences have no individual identity.
Counts are complete; `diagnostics.truncated` marks the detail limit. Its
`snapshotId` can be used with the original baseline in `problem_snapshots` for
additional pages while both snapshots remain retained. A snapshot evicted during
this call does not invalidate the report already being assembled.

Each test report exposes observed file modification time, SHA-256, counts and up
to 100 failure/error/skip details. Long detail strings carry a truncation marker.
Missing, changing, pre-baseline or unparsable files are incomplete evidence.
Counts aggregate only accepted files; `tests.complete=false` prevents treating
those partial totals as a full test run.

## Observation limits

The tool does not refresh files, save editors, schedule new validation, start tests,
or update an infobase. Validation covers all currently resolved metadata top
objects, while diagnostic filters stay exactly as captured. Disabled global
project checks or unobservable validation prevent a confirmed result.

Capture timestamps describe observations, not validation revision identity.
Concurrent source edits can change the project during collection. The caller must
select reports for the intended project, revision and test selection: JUnit file
timestamps/hashes do not establish that binding. A report newer than the baseline
can still belong to a different run. This is evidence with explicit provenance
limits, not an atomic certification of project correctness.
