# Insert a BSL method into an empty region

Beads: `edt-qzy`. The user approved addressing an existing empty region while
retaining flexible method-relative insertion and module-wide duplicate checks.

## Delivered behavior

`write_module_source(mode="insertIntoRegion", regionName=..., source=...,
expectedHash=...)` inserts one complete new method before the selected region's
matching end. Region and method names match without case sensitivity. The region
must be unique across the complete module, including conditional branches,
outside methods, and contain only blank lines or full-line comments. A uniquely
named empty region nested inside another region is allowed. No region is created.
Missing, ambiguous, malformed/unbalanced or nonempty regions are refused. A
duplicate incoming method name anywhere in the module also refuses the insertion.

The isolated `BslEmptyRegion` resolver uses the existing literal/comment mask,
balanced region stack and method spans. Original directive text is validated so
a masked string cannot conceal an invalid directive tail; raw contents determine
emptiness. Native shipped `Bsl.xtext` confirms RU/EN keywords, identifier ranges,
hash spacing, whitespace and trailing comments. Existing read-region helpers
and the shared method scanner are unchanged.

The new mode rejects `methodName`, `edits`, `oldSource`, `expectedSource`,
`overwrite` and `expectedMethodHash`; `regionName` is rejected for other modes.
The whole-module token is mandatory. Its conditional contract survives compacted
wire schemas. Receipt identity includes regionName only when supplied, retaining
legacy request digests. Existing source normalization, one-method validation,
module-wide duplicate checking and insertion spacing are reused.

Preview, human review and direct writes share preparation. Direct region writes
reuse the existing prepared full-byte snapshot commit, including fresh admission
checks and resource scheduling rules. Dirty-buffer/vendor guards and late full
snapshot refusal remain. LF/CRLF and UTF-8 BOM preservation use existing encoding.
No companion-plugin hooks, preferences, policies or dependencies were added.

## Verification

- Writer RED: 8 tests, 6 expected failures, zero errors. Region resolver RED:
  11 tests, 10 expected failures, zero errors.
- Combined focused GREEN: **330 tests, zero failures/errors**, including 176
  legacy writer tests, 105 shared module tests, 14 resolver tests and 8 new writer
  tests, plus receipt, schema compaction and late-commit regression coverage.
- Clean full Maven/Tycho verify on JDK25: **13,286 tests, zero failures/errors,
  21 skips; BUILD SUCCESS** in 9m52s.
- Independent read-only source review found **no actionable findings** after
  inspecting native grammar, boundary/masking/scope handling, arguments, duplicate
  checking, receipts, compacted schema and guarded commit reuse.
- The live-generated 108-tool golden changes only `write_module_source`; its
  generated reference page matches the frozen server. Golden check: 1/1 passed,
  fixtures clean. The business-project skill validator passed.

Live verification uses the frozen bundle SHA-256
`c79c82758099f9f9d588254274029ccd7f735b87066d4f150f69f56411540e6c`
in private EDT 2026.2.2.21 configurations, workspaces and copied fixtures.
Automated windowed checks use isolated Xvfb; the installed EDT and user projects
are not test targets. Evidence is retained under `/tmp/edt-empty-region/`.
All **1782 class payloads** in this frozen bundle match the clean build exactly;
timestamped packaging metadata may differ.

Standalone actual EDT: **12/12 cases passed** with clean fixtures, including
spaced English and nested Russian region insertion, exact surrounding bytes,
duplicate/payload/target refusals, preview/stale guards, receipt replay and
region-name binding, plus legacy insertion/batch/method-hash regressions.
Two initial error-text assertions were too specific (a duplicate error names
the incoming method; invalid payloads name Procedure/Function); only those
assertions were corrected, and both cases passed on rerun.

Four real-file LF/CRLF × BOM/no-BOM variants passed preview and actual insertion
while preserving the encoding. Setup used clean_project without fixture reset,
with explicit raw-byte assertions immediately after model refresh. A real
unsaved editor caused both new-mode preview and direct write to refuse,
preserving the dirty buffer, saved file, preferences and review counts.

Actual cold **HEADLESS** stdio host (HTTP disabled) passed new-mode preview,
application, identical receipt replay and repeated-insertion refusal. Its first
private fixture copy accidentally included a temporary region from the encoding
setup, so ambiguity correctly refused; the private Calc file was restored from
its Git baseline and the same flow passed. No product change was required.

With EDT-MCP, Comfort main/early/checks and Effector 1.1.8 actually ACTIVE,
the review-enabled selection passed **5/5** with clean fixtures. One additional
BOM/CRLF proposal inspection verified the exact prepared output bytes and
preserved surrounding source before cancellation. No real review was
automatically accepted. Both servers returned HTTP 200 ready health responses
before shutdown; Effector writes remained disabled. Official MCP conformance
0.1.16, spec 2025-11-25: **8 passed, 24 expected baseline failures, zero unexpected
failures; exit 0**. No new actionable findings were identified.

All private fixture trees were clean after checks. Owned EDT/Xvfb processes
stopped gracefully without forced kills; ports 18765/39721 closed.

The existing Comfort VerifyError is separately tracked as `edt-7ji`; this feature
does not suppress or fix it. Validation is bounded to the exercised Linux/EDT
version and scenarios. Windows/macOS and a full application e2e rerun are outside
this change. No push, release or PR is included.
