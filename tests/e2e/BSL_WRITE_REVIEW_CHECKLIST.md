# Attended BSL review verification

Use a disposable EDT installation/workspace and a copied project. The normal
unattended e2e suite explicitly opts out through `plugin-customization.ini`.
This checklist and `review_probe.py` exercise review enabled; no MCP acceptance
endpoint is shipped. Unchecked cases are unperformed checks, not passing skips.

## Prepare

1. Build and verify the deployed bundle matches the new artifact.
2. Import the copied project, wait until `list_projects` reports ready, and
   record its clean source baseline. Preserve the original project's status.
3. Use the normal General preference with review enabled. Destructive consent
   may be allowed for a test stand; it must not bypass review.
4. Run `python tests/e2e/review_probe.py --port PORT --project PROJECT --module
   PATH --directory COPY`. Keep the returned ID; do not submit another append
   to obtain its status. Poll with the same command and `--review-id ID`.

## Cases

- [x] A real changing request returns awaitingApproval, a consumable review ID,
  and applied=false. All project source/model files are unchanged.
- [x] The diff opens in a nonmodal editor tab. Another editor, navigation, and
  scrolling remain usable; the header and Apply/Reject buttons stay visible.
- [x] Both sides resist typing, merge-copy and Ctrl+S; closing does not accept.
- [ ] Clipboard paste into both sides is checked separately.
- [x] Repeating the same pending request reuses the ID/tab. A different request
  for that module reports reviewPending with the existing ID.
- [x] Reject leaves files unchanged. Closing an awaiting tab also rejects it.
- [x] Apply commits exactly the displayed output once, preserves BOM/line
  endings, and exposes the original result. Double-click does not repeat append.
- [x] An external base-byte change makes the proposal stale without overwrite.
- [x] A dirty target before preview is refused. Human typing in another editor
  after preview is never saved or overwritten on Apply; the proposal fails
  without mutation.
- [ ] Repeat the dirty-editor check in a second workbench window and with a
  hidden text buffer. Buffer aliases have separate unit coverage.
- [x] A new file displays an empty left side and is absent before Apply.
- [ ] Repeat creation with absent parent folders and an unexpectedly created target.
- [x] A byte-identical proposal returns noChange and does not mutate metadata.
- [x] Disabling review applies only to new calls. Pending tabs keep their own
  decisions. Restoring defaults enables review again.
- [ ] Project close/reopen and server stop/restart revoke old tab actions.
- [ ] Expiry disables actions, including Apply racing the deadline.
- [ ] UI opening failure/timeout never falls back to writing or opens a usable
  late proposal.
- [ ] A follow-up/export failure after source write reports mutationCommitted=true;
  an opaque write failure reports mutationOutcomeUnknown, preventing blind retry.
- [ ] Unload removes listeners/timers/pending payloads; an entered write retains
  its real outcome. Review IDs are lost on restart.

## Evidence

Record actual completed cases, project/module, bundle fingerprint, UI screenshots,
before/after byte checks, registry/protocol results, and classified EDT log errors.
Registry, deadline, race, encoding and headless-refusal unit tests complement
this checklist; they do not prove the interactive workbench cases.

Completed cases above were exercised on 2026-10-28 in EDT 2026.2.2.21, with
`GTK_THEME=Adwaita:light`, using a private copy of KASL. Existing-file cases used
`DataProcessors.МодельТекста.ObjectModule`; creation used
`DataProcessors.КонструкторСхемыЗапроса.ObjectModule`. Exact raw bytes, marker
count, project Git status/diff and MCP status were checked. The copied project
was restored afterwards; the original KASL was unchanged. The tested UI bundle
was `1.0.0.202610281907`; later alias-guard changes have unit coverage.

Evidence is exported to `/tmp/edt-bsl-review-verification-20261028/` on the test
machine. Unchecked cases above have not been verified interactively. Project
lifecycle, deadlines, headless refusal, cancellation and post-write error
classification have unit coverage. Full product e2e, protocol conformance and
older EDT versions are outside this verification run.
