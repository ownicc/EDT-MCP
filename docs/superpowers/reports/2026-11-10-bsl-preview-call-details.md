# BSL edit preview and detailed call sites

Beads: `edt-jum` and `edt-9qd`. The user approved these two additions and
explicitly left metadata-file reachability and query-result schemas to Comfort.

## Delivered behavior

`write_module_source(preview=true)` prepares the existing edit modes through the
same validation, normalization and detached byte preparation used for review.
It returns JSON with exact-byte/file-presence `wouldChange`, read-compatible
normalized hashes, syntax-check state, normalization details and a bounded diff.
It neither writes source/metadata nor registers or opens a review. Combining it
with `requestId` is refused before receipt admission; a preview is not an
executable patch or permission for a subsequent write. A real write repeats its
guards and follows the current human-review setting. The diff reuses the
existing EDT/JGit formatter, bounded to 200 formatted lines and 64 KiB of body.

`get_method_call_hierarchy(includeCallDetails=true)` returns direct saved-source
callers/callees with actual argument text, resolved formal names, exact UTF-16
ranges with exclusive ends, caller/callee identities and a native client/server
call kind when known. Unavailable values remain explicit null/unknown; no target
is guessed from spelling. Argument text is bounded to 4096 UTF-16 units and 100
arguments per site. Detailed outgoing aggregation and transitive walks are
refused with a direct-call hint. Default hierarchy output remains unchanged.

These details help inspect the callers affected by a signature change, locate
unexpected positional values and examine client/server crossings in context.
The feature is static analysis, not execution or a complete dynamic call graph.

Neither feature adds a global key binding, Display filter, Job listener,
type-computer override, foreign preference/policy write or dependency on either
companion plugin. No reference implementation was copied.

## Verification

- RED observed missing opt-in schema, detail response typing and unsupported
  detail-combination refusals before implementation.
- Initial focused GREEN: 253 tests; final focused rebuild after source-link/null
  handling corrections: 77 tests. Both had zero failures/errors.
- Clean complete Maven/Tycho verify on JDK25: **13,247 tests, 0 failures,
  0 errors, 21 skips; BUILD SUCCESS** (9m58s).
- Independent review found a no-op test wording mismatch, corrected before live
  verification; no outstanding actionable source findings. The remaining BOM
  uncertainty was resolved by the live checks below.
- Both generated tool pages came from the live server. The complete 108-tool
  golden exactly matches the regenerated live catalogue; only the two intended
  schemas changed. Golden checks: four passed, two environment skips. The
  business-project skill validator and `git diff --check` passed.

All live runs used separate EDT 2026.2.2.21 configurations/workspaces under Xvfb,
private p2/profile/user-home locations, copied fixture checkouts and the same
frozen bundle SHA-256:
`a5cc8b63bafe94927d64fa50663ad4436c09cd851aead6c69322fc9c39efc26b`.
All 1778 class payloads exactly match the final clean build; packaging version
metadata differs between the earlier frozen artifact and the final build.

| Isolated configuration | Review setting | Focused live results |
| --- | --- | --- |
| EDT-MCP | Disabled | 10 passed, clean fixtures |
| EDT-MCP + Comfort | Enabled | 7 passed, clean fixtures |
| EDT-MCP + Effector core | Enabled | 7 passed, clean fixtures |
| EDT-MCP + Comfort + running Effector bridge | Enabled | 7 passed, clean fixtures |

The 31 cases include detached previews, syntax/stale-hash refusals, receipt
non-admission, exact argument/range data, explicit-false legacy output parity
and ordinary-write baselines. Additional probes passed in all four configurations:
BOM module callers/callees with unchanged bytes, a real unsaved editor preserved
through a refused preview, unchanged immediate before/after preferences and zero
new review records/UI inputs. Opening an editor itself legitimately updates
Comfort's recent-position preference; that is separate from the preview call.

Comfort main, early and checks bundles and Effector 1.1.8 were actually ACTIVE,
not merely present. On the final stand both EDT-MCP port 18765 and Effector port
39721 returned HTTP 200 with ready health responses before/after checks. Effector
bridge auto-start was enabled only in initial private test configuration;
authentication controls and its disabled write setting were preserved.

Official conformance 0.1.16 against the all-three stand, protocol 2025-11-25:
**8 passed, 24 expected baseline failures, no unexpected failures; exit 0**.

## Existing companion-plugin issue and boundaries

Comfort 1.0.9.202611090903 logs `VerifyError: Bad type on operand stack` from
`BslDocumentationComment.parseOldFormat` at bytecode offset 1410, through
`BslStructureInsertCommentTypes$ComfortTreeTypeSystem`. The same error was
reproduced at startup/indexing with the installed **prefeature** MCP bundle
1.0.0.202611092120. Ordinary hierarchy/symbol reads and the new focused scenarios
passed despite that background issue. It is tracked separately as `edt-7ji`;
errors were not suppressed and Comfort hooks/settings were not changed to hide it.

This is bounded coexistence evidence for the exercised Linux/EDT version and
scenarios. Windows/macOS, a separate headless-host run and a complete application
e2e rerun were not performed. No claim of universal plugin compatibility or
release certification is made.

Evidence: `/tmp/edt-preview-call-details/` contains RED/GREEN/full-build logs,
`mcp`, `comfort-review`, `effector-review`, `all-review` reports and extras,
`comfort-baseline-review` reproduction, actual bundle states and conformance log.
The user's installed EDT, source projects and companion-plugin configuration
were not changed. No push, release or PR is included.
