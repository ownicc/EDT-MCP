# Verification report (edt-8qy.5)

Implemented `verification_report` on `feat/edt-8qy-5-verification-report`, then
integrated locally into `master`. No deployment or publication was performed.

The report pins an existing diagnostic baseline, observes validation of all
metadata top objects with the existing bounded wait, captures the same filters,
and returns new/fixed/remaining counts, limited details, observation times,
selected JUnit evidence, limitations and a human-readable summary. It never
refreshes/saves sources, launches tests or updates an infobase.

Test reports are explicit absolute paths on the EDT server, limited to ten
files of 2 MiB each. Missing, stale, partial, inconsistent or changing evidence
does not pass. Skipped/disabled tests do not pass. Nested suites count each case
once; multiple assertion failures count as one failed testcase. Timestamps and
hashes do not establish project/revision identity; this limitation is in every
report and the guide.

## Verification

Evidence directory: `/tmp/edt-verification-report/`.

- Original snapshot baseline: Maven verify with `ProblemSnapshotsToolTest,DiagnosticSnapshotsTest` passed.
- New report tests first failed because the new classes were absent; initial
  implementation passed the focused report/snapshot/JUnit tests.
- Full canonical `bash source/compile.sh` ran **13920 tests, 3 failures, 0 errors,
  31 skips**. Failures were the schema/execute parity check and two documentation
  checks: numeric parameters were read through an unrecognized helper idiom,
  and generated pages/indexes had not been present when those checks ran.
  Reads are now explicit and documentation/catalog entries are updated.
- Final Maven reactor `verify` passed **83 tests, 0 failures/errors/skips**,
  including all three failed checks, both new suites, existing snapshot/JUnit
  suites, validation waits, tool contracts, host capabilities, toolsets and
  read-only preset policy. See `final-checks.log`. The entire suite was not
  rerun after these fixes; do not describe the initial full run as green.
- Independent read-only review found two real strict-parser issues: GoogleTest
  disabled cases counted as passed, and multiple failed assertions erased the
  failed verdict. Both were reproduced in `review-red.log` (two assertion
  failures) and fixed in `review-green.log` (six tests passed). The final Maven
  verification includes these regression tests.
- Python E2E runner: **319 passed**. Documentation generator: **5 passed**.
  Python compilation and `git diff --check` passed.
- Final real **HEADLESS EDT 2026.2.2.21 / JDK 25** used a separate configuration,
  workspace and Git-tracked fixture copy; installed EDT files were only read.
  All **3 live scenarios passed**, collecting 14 report responses: actionable
  refusals, unchanged diagnostics with passed/failed/error/skipped/missing
  selected JUnit evidence, and native BSL errors appearing and disappearing
  after saved edits. Shared harness assertions validate structured output
  schemas over the real stdio/IPC transport. Evidence: `live-final/`.
- The first live attempt exposed an incorrect test expectation: restoring the
  original method legitimately reintroduces its existing naming-specific style
  warning relative to the broken-method baseline. The final test separately
  checks fixed problems and compares the restored module to the original
  baseline. Product behavior was correct.
- Final fixture Git status is empty and owned EDT/bootstrap processes exited.
  The packaged p2 plugin matches the live-tested bundle byte for byte; ZIP CRC
  checks pass. Archive: `/tmp/edt-verification-report/dist/MCP-EDT.verification-report.zip`.

Live JUnit files are synthetic parser/aggregation fixtures, not claimed business
test executions. Windows, macOS and a windowed product session were not exercised.

## Local master integration

Preserved the newer master commits for fork CI documentation, the readiness
contract snapshot and the current tool-choice benchmark. The benchmark's strict
audit detected the added tool; its catalog was re-exported from the built plugin,
with exactly `verification_report` added and all existing descriptors unchanged.
The tool has an explicit exclusion pending an authored model request. Repinned
hashes and the unrun audit preserve the absence of model measurements.

The merged tree passed 83 focused Java checks, 319 E2E runner tests, five docs
generator tests, 16 offline benchmark tests and the plugin/catalog audit.
These checks cover the integration differences; no additional full Java or live
EDT run was performed for the merge. Historical benchmark inputs are unchanged.
