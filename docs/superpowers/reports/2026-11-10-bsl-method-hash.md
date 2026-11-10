# Method-scoped BSL replacement guard

Beads: `edt-ahm`.

## Delivered behavior

`read_method_source` now returns `methodHash` for a unique, complete method
that the existing replacement scanner can safely address. The token covers the
exact replaced span: contiguous documentation and ampersand directives,
declaration, body and terminator. The displayed saved source, whole-module
`contentHash` and method token come from one file read. Hash input joins the
span's lines with LF without adding a terminal newline; UTF-8 BOM and LF/CRLF
do not change the method token. Model-only fallback still displays known native
methods, including scanner-unlocatable declarations, but returns neither token.

`write_module_source(mode="replaceMethod", expectedMethodHash=...)` can omit
`expectedHash`. It checks the target against the current saved module and
prepares the replacement from that module, preserving unrelated edits made
before preparation. Supplying both tokens requires both to match. Blank tokens,
changed target text, missing, ambiguous or unaddressable targets are refused.
Insert modes and batch replacements retain their whole-module guard and reject
the new token. Preview uses the same target validation; receipt identity includes
the new argument while requests without it retain their existing digest.

After preparation, the full raw-byte module snapshot must still match before
commit. A neighboring edit made during review or between preparation and direct
commit therefore invalidates the proposal. Direct writes with the new token
reuse the existing guarded commit under the workspace scheduling rule; windowed
hosts retain the UI admission check and headless hosts use the resource rule.
Dirty buffers, vendor support, method name/kind checks and human review remain
enforced. The method token guards text, not logical dependencies or surrounding
preprocessor context; callers can retain the whole-module guard for that scope.

No global hook, key binding, type-system override, companion-plugin dependency,
foreign preference or policy write was added.

## Verification

- Focused RED demonstrated missing schema and write guards before implementation.
  Final focused GREEN: **234 tests, zero failures/errors**.
- Clean complete Maven/Tycho verify on JDK25: **13,264 tests, zero failures,
  zero errors, 21 skips; BUILD SUCCESS** in 9m52s.
- Independent review found a native-readable split/inline declaration fallback
  regression. The regression test failed before the correction and passed after
  restoring token-free native fallback. No actionable review findings remained.
- An explicit late-commit regression changes neighboring B after preparation
  while A's token remains valid: commit refuses the stale full snapshot before
  mutation and preserves the changed bytes.

All live runs use private EDT 2026.2.2.21 configurations/workspaces, copied
fixtures and the frozen bundle SHA-256
`6069aacb40720906105c9d4b779e40df3417cddc78f04efe5eb86dd5a201c92c`.
Windowed runs use isolated Xvfb; the actual cold headless run uses stdio without
HTTP or a workbench. The headless A/B scenario passed: read A, edit B, preview
and apply A with its old method token, preserving B. The exact reader responses
match the windowed pins; only the new methodHash lines changed those pins.
All **1779 class payloads** in the frozen bundle exactly match the clean build;
the timestamped packaging metadata may differ.

The standalone live selection passed **18 cases**, with one expected skip for a
review-enabled-only scenario. Two initial assertions were corrected to match
the intended contracts (native fallback has neither token; whole-module stale
errors point to the module reader), and both cases passed on rerun.
Four additional real-file variants (LF/CRLF, with/without UTF-8 BOM) passed with
the same method token, preserving B and the original file encoding. Each setup
explicitly asserted that the intended bytes survived native-model refresh.
A real dirty editor caused refusal without changing its buffer, saved bytes,
preferences or review counts; the private editor was then closed without saving.

With EDT-MCP, Comfort main/early/checks and Effector 1.1.8 actually ACTIVE,
the review-enabled selection passed **9/9 cases**, with clean fixtures. It
includes a method-hash preview without opening review, refusal of unsupported
arguments, and a real pending proposal cancelled without applying it.
The four encoding variants also passed with review enabled: read-only proposal
inspection checked the current complete base SHA-256 and exact proposed output
bytes containing A=42 and latest B=20, including BOM/delimiter preservation.
Each proposal was cancelled; no real review was automatically accepted.
The dirty-buffer probe also passed on this combined stand, preserving buffer,
disk, preferences and review counts. Both EDT-MCP (18765) and the actual Effector
bridge (39721, writes disabled) returned HTTP 200 ready responses. Official MCP
conformance reported **8 passed, 24 expected baseline failures**, with no
unexpected failures and a passing baseline check.

The generated 108-tool catalogue changes only `write_module_source`.
Both generated tool pages, exact windowed reader pins and the unchanged
headless/windowed parity contract were checked against the live hosts.
The business-project skill validator and `git diff --check` passed.

## Boundaries

The existing Comfort `BslDocumentationComment.parseOldFormat` VerifyError is
tracked separately as `edt-7ji` and was previously reproduced with a prefeature
MCP bundle. This change neither suppresses that error nor changes Comfort's
hooks or settings. The exercised coexistence scenarios are evidence for this
Linux/EDT version, not a universal compatibility claim. Windows/macOS and a full
application e2e rerun were not performed.

During the encoding checks, the old supplemental preview/call-details BOM
probe was found to reset its BOM fixture before reading it. The prior report
and closed issue notes now withdraw those four BOM-specific claims; its
independent no-write/dirty-buffer results remain valid. The current four
method-hash encoding checks use explicit byte assertions and do not replace
the unperformed old call-detail BOM scenarios.

Evidence is retained under `/tmp/edt-method-hash/`. The installed EDT and user
projects are not test targets. No push, release or PR is included.
