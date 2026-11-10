# Proxy reliability implementation plan

> Execution: apply the three fixes authorized in the conversation, inline with TDD.

**Goal:** bound backend response reads, preserve uncertainty after a lost tool response,
and align contributor instructions with the current build and CI.

**Architecture:** enforce the HTTP exchange deadline at Backend's shared streaming body
handler, without buffering routed responses. Close timed-out streams and cancel deadline
tasks when callers finish. Keep the existing error envelope, but explicitly describe
unknown tool outcomes rather than recommending an automatic repeat.

**Constraints:** Java 17 output; no new dependencies; preserve session recovery, workspace
verification, byte-for-byte relay and structured-content opt-out. Work on a local branch,
merge locally to master after verification, do not publish the working branch.

## Tasks

- [x] Add and run failing real-HTTP regressions for buffered and streamed body stalls,
  the discovery timeout override, and a completed mutation whose response is lost.
- [x] Add a small deadline-aware response stream used by Backend's streaming requests;
  include time spent waiting for headers, cancel timers on close/EOF, and report timeout
  as an IOException rather than accepting a partial result.
- [x] Replace the unsafe tool-call retry instruction, preserve safe discovery guidance,
  and update proxy documentation. Do not attempt a second HTTP response after relay starts.
- [x] Update tests/TESTING.md and compile.sh help for JDK 25, Tycho 5, pinned Maven,
  automatic headless CI setup, and the separate standalone proxy tests.
- [x] Run the complete proxy Maven verify, shell syntax/help checks, and documentation
  consistency review. Plugin source and schemas are unchanged, so plugin redeploy,
  metadata e2e and plugin conformance do not exercise this standalone proxy change.
- [x] Review the final diff, commit only task files, and merge locally into master while
  preserving the existing .gitignore and AGENTS.md changes.

## Review focus

Deadline cleanup, stale-session retries, blocked reads after headers, partial responses
after client headers are committed, and avoiding duplicate mutations after a lost reply.

## Verification record

- Baseline: 194 proxy tests passed.
- RED: all five BackendTransportIT regressions failed for the expected behavior.
- GREEN: all five passed, then the complete proxy verify passed on both Java 17
  and Java 25 (199 tests on each).
- Shell syntax/help and the business-project skill validator passed.
- Independent read-only review found no actionable issues.
