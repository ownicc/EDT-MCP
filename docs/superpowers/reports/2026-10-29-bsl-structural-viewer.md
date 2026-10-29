# Native structural BSL viewer prototype verification

Implemented on `feat/bsl-structural-viewer`, based on the existing reviewed-write
implementation in `feat/bsl-write-review` and the difftastic experiment in master.
The other branch/worktree was read without modification.

## Result

Native read-only SWT text columns inside the existing CompareEditorInput use
EDT's BSL lexer, token attribute mapper and configured colors. Difftastic provides
structural changed ranges and row alignment. The viewer has line numbers,
previous/next changes and synchronized vertical scrolling. The existing registry,
Apply/Reject buttons and exact-byte commit guards are unchanged.

An absolute JVM property `edt.mcp.difftastic` opts into the external BSL-enabled
engine. Without it the native compare now requests the BSL content type instead
of plain text. Process failure, invalid output or a ten-second timeout shows the
standard BSL compare and a notice. Closing the structural controls cancels the
worker and its process. Temporary source snapshots are cleaned up.

## Evidence

- Canonical `source/compile.sh` build succeeded with JDK25/Maven3.9.11.
  Selected viewer, adapter and existing approval/write-commit suites:
  **40 tests passed, zero failures, errors or skips**. Full release-wide suites
  were not rerun. Logs: `/tmp/edt-structural-viewer-tools/final-build.log`.
- Red/green evidence covered BSL viewer selection, omitted JSON chunks, UTF-8
  boundaries, source/span mismatches, Cyrillic/emoji columns, final newlines,
  EOF markers and invalid alignment. Real child-process tests cover snapshot
  cleanup and cancellation/termination.
- The Java adapter consumed all 22 frozen experiment responses. The real
  BSL-enabled engine correctly isolated the numeric-change fixture.
- All 58 copied Kasl modules were processed with a whitespace probe: 57 used BSL
  mode; `РаботаСКоллекцией` explicitly fell back to text because the grammar
  reported two parse errors. No adapter failure remained after the EOF fix.
- A real `searchReplace` proposal opened the native structural review tab for
  `CommonModules/ОбщийКлиент/Module.bsl` in EDT2026.2.2.21. Accessibility state
  confirmed `Structural BSL comparison`, `Current source`, `Proposed source`
  and the pending approval state. The original module bytes remained unchanged.
  The tab was left pending for user inspection. No acceptance was automated.
- SHA256 verification confirmed all 165 copied source files and the original
  Kasl source files still matched the preparation snapshot.
- A separate EDT installation/configuration and persistent engine copy were
  staged under `/home/ownicc/Workstation/Kasl-diff-test`; the normal EDT
  installation was not modified. Its launcher preserves `GTK_THEME=Adwaita:light`.

## Review and limits

One independent read-only reviewer found no blocking issues and one minor
disposed-widget dispatch issue. The implementation now captures Display on the
UI thread and tolerates display disposal before dispatch. Final selected tests
and the build ran after that change. No second independent review was run.

The user inspected the live window and confirmed its appearance, requesting
stronger change backgrounds on white. Added green was strengthened to `#9cdda8`
and removed red to `#f3b3b3`; the subsequent color-only build skipped unchanged
logic tests. Scrolling feel and all approval actions still need attended checks.
Window-specific screenshot capture is unavailable on the current
Wayland backend; desktop capture did not show the EDT window and was discarded.
Accessibility evidence is not claimed as a visual screenshot test.

This is an opt-in Linux prototype, not a release certification. Binary packaging
for other operating systems, move labels, structural query-string comparison,
large-module UI responsiveness and live apply/reject/stale/dirty revalidation of
the final surface remain follow-up checks. No release, push or PR was created.

## Theme and gutter follow-up (edt-5sw)

The toolbar now switches Light/Dark (new reviews default to Dark) without
replacing text or recalculating the comparison. Light uses EDT syntax attributes;
Dark uses BSL token categories on `#1e1e1e`. Changed tokens use stronger green
`#26703a` / red `#9c3030` backgrounds and white text, with contrast ratios 6.07
and 7.29 respectively. Changed original line numbers are green on the right and
red on the left in both themes. The existing structural span map determines
their color; padding has no number and formatting-only lines remain neutral.

The canonical JDK25/Maven3.9.11 build passed **14 selected tests**, with no
failures, errors or skips (theme contrast, structural adapter and review input).
The new background-separation check failed on the old palette before the fix.
Logs: `/tmp/edt-structural-viewer-tools/theme-{red,green}-build.log`.
A fresh independent reviewer found no blocking issues.

The separate Kasl EDT installation loaded bundle `1.0.0.202610291742`.
The attended example opened with the Dark selector, Current/Proposed source
panes and Apply/Reject controls in the accessibility tree. The helper confirmed
`awaitingApproval`, `applied=false`, `mutationCommitted=false` and unchanged
module bytes. The tab remains pending for user inspection. A background combo
click was unverifiable and a fresh tree still showed Dark; switching was not
confirmed in this run. Window screenshot capture remains unavailable, so these
checks do not establish the rendered colors, scrolling or approval behavior.
