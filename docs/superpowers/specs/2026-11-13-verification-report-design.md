# Verification report (edt-8qy.5)

Build the MCP report shown in the conversation: normalized diagnostic scope,
before/after observation times, validation state, new/fixed/remaining problems,
explicitly selected test results, and an honest overall verdict.

## Approach

Add `verification_report` to the existing problems group/project toolset.
It takes `baselineId` from `problem_snapshots(action=capture)` before editing,
waits for the project's metadata validation through the existing bounded
`BuildUtils.waitForObjectValidation`, captures the same diagnostic scope, and
reuses signature-count comparison. Validation runs on all metadata top objects,
even when diagnostic filters are narrower. It does not schedule a rebuild,
refresh files, save editors, launch tests, or update an infobase.

Selected test evidence is `testReports`: up to ten explicit absolute local
JUnit XML paths on the EDT server. Reuse the secure XML parser, adding strict
complete-report parsing without changing the legacy YAXUnit parser contract.
Missing, malformed, empty, contradictory, stale or changing reports are
incomplete evidence. Failures/errors are failures; skips never mean all tests
passed. No reports means `notSelected`, explicitly limiting the verdict to
diagnostics. File timestamps and hashes describe observed files; they do not
prove which project revision a test runner executed.

Alternatives: automatically launching YAXUnit would reuse a runner, but its
default database update violates this report's boundary. Accepting caller-supplied
test counts would be shorter, but would not verify evidence. An EDT panel adds
UI work without improving the requested MCP scenario.

## Contract and limits

Required `baselineId`; optional `testReports`, `waitSeconds` (default 5, 1–15),
`limit` (default 100, 1–1000). Snapshot scope comes exclusively from the baseline.
Keep immutable snapshot references during comparison, including concurrent
retention eviction. Include complete category totals and explicit detail
truncation. No comparison counts are invented when capture fails.

Verdicts: `regression` for new diagnostics or observed failed tests;
`incomplete` for unconfirmed validation/capture/test evidence or skipped tests;
`noNewProblems` otherwise. This last verdict does not mean an error-free project,
and absent test selection does not mean tests passed. Include structured JSON
and a compact human-readable summary. Observations are not an atomic source
revision guarantee; a baseline is historical stored markers, not a freshness
certificate.

## Verification

Unit tests cover same-count diagnostic replacement, remaining errors, pending
and unobservable validation, capture failure, bounded output, snapshot eviction,
and passed/failed/skipped/stale/malformed JUnit evidence. Run the Java suite and
focused live MCP checks on an isolated EDT host with copied fixtures. User EDT
and its workspace stay untouched. Develop and validate on
`feat/edt-8qy-5-verification-report`; do not publish the branch or create a PR.
