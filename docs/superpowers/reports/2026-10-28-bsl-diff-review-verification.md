# BSL write review implementation verification

Branch: `feat/bsl-write-review`. Implementation is in the managed worktree
`/home/ownicc/.codex/worktrees/bf8e/EDT-MCP`. No push or merge was performed.

Changing BSL writes require human approval by default. A native nonmodal,
read-only comparison has Apply/Reject controls in its header. General
preferences can disable review for future calls. Existing proposals keep their
decisions. The status tool carries the review ID and actual mutation outcome.

## Evidence

- Final selected Tycho feature/protocol/documentation suites: **325 passed**,
  zero failures, errors or skips; canonical `source/compile.sh` succeeded.
- Live direct-write compatibility filter: **43/43 passed**, fixture clean.
- Live status filter: **3/3 passed**; tools-list golden: **1/1 passed**.
- Documentation validator: 13 skills, 103 documented/registered tools plus proxy.
- Attended native UI checks ran against a private copy of KASL in EDT
  2026.2.2.21, with `GTK_THEME=Adwaita:light`. The original project was unchanged.
  Completed and unperformed cases are separated in the
  [interactive checklist](../../../tests/e2e/BSL_WRITE_REVIEW_CHECKLIST.md).
- Screenshots, raw MCP results, build/test logs and the update-site archive are
  exported to `/tmp/edt-bsl-review-verification-20261028/` on the test machine.

The live write filter tested the bundle before the final two race fixes. The
final fixes have regression tests against the real Eclipse scheduling manager
and shared opening acknowledgement. The final bundle was redeployed for the
status/golden smoke checks. UI behavior was not exhaustively replayed afterwards.

The initial baseline full Java suite passed 11,966 tests with five skips; this
is baseline evidence, not a final full-suite claim. Full product e2e, external
protocol conformance, older EDT versions and unchecked attended scenarios were
not verified. This is not a complete release certification.

## Independent review

One fresh read-only reviewer found two Important issues and no Critical or
Minor issues. Both were fixed in `96994c18`, followed by the 325-test green run:

1. Cancelling a queued asynchronous UI job retained its project scheduling
   rule, potentially blocking a simultaneous UI save. A real Eclipse contention
   test failed with cancellation alone. Revoking mutation entry and explicitly
   completing the asynchronous job releases the rule; late callbacks cannot write.
2. A concurrent matching call could return before the first editor existed.
   Matching callers now wait for the same bounded creation acknowledgement and
   receive the opening failure together. A late editor cannot revive failure.

Deferred minor findings: none.

## Decisions made during execution

1. Initially selected targeted red/green feedback plus full tests per task to
   avoid rerunning slow timeouts on every local step. Risk: cross-suite errors
   surface later. Superseded by decision 2.
2. Used relevant suites per task, initially intending one final full suite,
   because broader passing suites did not add focused evidence. Risk: unrelated
   regressions surface at integration. Final scope was narrowed by decision 6.
3. Added a package-visible Executor seam alongside Clock for deterministic
   registry races; production uses Eclipse Jobs. Risk: tests do not substitute
   for scheduler integration.
4. Used native UIJob with the project rule instead of manual rule transfer.
   Risk: interaction with synchronous UI saves. Independent review found that
   interaction defective; the real-rule regression test and explicit pre-entry
   completion address it while preserving UIJob.
5. Deferred widget checks until routing was integrated. Risk: layout/runtime
   problems appear later. Actual KASL UI checks are recorded separately; no
   unperformed UI case was counted as a passing skip.
6. After the user challenged excessive verification, limited final validation
   to feature/protocol/docs tests, live write/status/golden checks and one
   independent review. Risk: defects outside those suites remain unobserved.
7. Accepted the reviewer's unexecuted release-wide/older-platform/live cases as
   explicit verification gaps. Risk: environment-specific defects remain
   unobserved. No release-wide success is claimed.
