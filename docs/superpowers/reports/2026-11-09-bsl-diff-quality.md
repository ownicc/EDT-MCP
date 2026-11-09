# BSL diff quality and Kasl installation — 2026-11-09

Beads: `edt-mmh.5`, under `edt-mmh`. Design and implementation plan are in the
adjacent specs/plans directories. User selected marker variant A.

## Result

- Full-file comparison retains source order. Region and conditional branch changes
  can identify a moved method without making stable neighbours look moved.
- One jump badge sits beside the declaration; a thin gutter rail marks its extent.
  No repeated arrows, blue body fill or bold moved line numbers.
- The selected move opens a detached, read-only method comparison in another tab,
  with original line numbers and no Apply action. Equal bodies say so explicitly.
- Fine differences stay within changed lines/tokens, including multiline lexemes.
  New methods retain syntax colours and use subtle backgrounds.
- Light/dark source backgrounds are explicit. The attended Kasl run exposed a
  light-mode/dark-GTK mismatch; its native regression failed before the two-line
  fix and passed afterwards with `GTK_THEME=Adwaita:dark`.
- Copied experiment runtimes now use private p2 data areas. Older copies had shared
  the normal EDT profile; that profile was recovered through standard p2 revert
  from its matching original snapshot before installing the verified plugin.

Ambiguous duplicate identities and malformed input retain conservative fallbacks.
The standard viewer remains the default; this installation opts into JGit with
`-Dedt.mcp.bslDiff=jgit`. No runtime dependencies were added.

## Verification

- Clean complete Maven/Tycho verify: **12074 tests, 0 failures, 0 errors, 6 skips**.
  Log: `/tmp/edt-diff-quality/clean-full-verify.log`.
- An initial non-clean run used stale inlined test constants. Clean compilation
  resolved those failures; preference source/expectations were not changed.
- After the final GTK fix: focused Maven verify **28 tests, 0 failures/errors**;
  `/tmp/edt-diff-quality/gtk-theme-focused-build.log`.
- Final actual EDT/SWT check under Xvfb and dark GTK: **95 corpus cases**, complete
  monotonic source coverage, bounded spans, both syntax palettes, move badge and
  jump, equal-text notice, separate method tab. All pass; evidence:
  `/tmp/edt-diff-quality/gtk-dark-green.json`.
- Independent review found a same-sequence JGit comparator error and BOM-prefixed
  directive loss. Both were reproduced and fixed with parser/native regressions.
- `git diff --check` and Python runner compilation passed.

## Installed target

Workspace: `/home/ownicc/.local/share/1C/1cedtstart/projects/Kasl`.
EDT: `/home/ownicc/.local/share/1C/1cedtstart/installations/1C_EDT 2026.2/1cedt`.
Local development plugin: **1.0.0.202611091928**.
Installed artifact SHA-256:
`0b06bf366a7229ccadc7975393b00a926affc072b1624601457e7c749d8382d5`.
The installed JAR equals the final built/native-tested artifact byte for byte.

Backup of original configuration, ini, profile and plugin 1.0.3:
`/home/ownicc/.local/share/1C/1cedtstart/backups/edt-mcp-20261109-183756`.
Installation and native evidence are in `/tmp/edt-diff-quality`.
The real installation was opened against the requested workspace; no new workspace
or persistent launcher was created. The attended run uses X11 because native
Wayland control could inspect buttons but could not reliably select tree nodes.

The live example proposes a method move, local edits, a new function and a removal
in `CommonModules/РаботаСМассивом/Module.bsl`. It is a review only; no Apply action
is performed. The KASL module bytes are checked before and after the preview.
Final review: `d335ceba-302b-474b-8814-fb9e737e10e3`, `awaitingApproval`,
`applied=false`, `mutationCommitted=false`. All 165 original snapshot files
retain their prior SHA-256 hashes. The actual method tab shows equal texts at
472–474 / 185–187. Screenshots: `/tmp/edt-diff-quality/Kasl-installed-diff.png`,
`Kasl-method-comparison.png`, `Kasl-added-function.png`.

All development is local. Nothing was pushed and no release or PR was created.
The epic and unrelated MCP fragment/full audit work remain open.
