# Manual BSL diff opt-in

Added **MCP Server → General → Use the new BSL diff viewer**, with English and
Russian labels. The user explicitly chose manual opt-in; new and existing
workspaces default to the standard viewer. Apply/OK saves the checkbox, and
Restore Defaults clears it. The next MCP review reads the saved preference
without restarting EDT; existing comparison tabs retain their viewer.

The explicit `-Dedt.mcp.bslDiff=jgit` override still forces the method-aware
viewer. Its precedence is explained in the checkbox tooltip and README. Existing
Difftastic selection, computation fallbacks and human approval safeguards remain.

## Verification

- RED: focused build failed because the preference selection method did not exist.
- GREEN: 25 focused tests passed, including actual SWT GeneralTab load, Apply,
  defaults, unsaved changes, locale parity and the viewer selection predicate.
- Separate Tycho workbench run under Xvfb: one test passed, exercising actual
  comparison controls in standard → method-aware → standard order without either
  viewer JVM flag. Each proposal stayed awaiting approval until close rejected
  it; the fixture callback fails if any write is attempted.
- Clean full Maven/Tycho verify on JDK25: **13,233 tests, 0 failures, 0 errors,
  21 skips; BUILD SUCCESS**. The added workbench test is intentionally skipped by
  the ordinary headless unit run and was executed separately above.
- Business-project skill validator passed; `git diff --check` passed.
- Independent review found no actionable defects.

The workbench harness logged a platform QL injector initialization failure
(`IBmModelManager` binding missing in the partial test workbench); the viewer
selection assertions passed. The installed EDT was not redeployed, and Windows,
full live MCP e2e and protocol conformance were not rerun for this settings-only
change. No tool contract or transport changed. Logs are in
`/tmp/edt-mcp-diff-settings/`.

## EDT.Comfort reference

Read `references/EDT.Comfort/plugin/src/tormozit/CompareEditorCurrentLinesHook.java`:
`switchToBslViewerIfNeeded` switches existing compare panes to EDT's BSL viewer.
It is useful evidence for a future integration with ordinary EDT comparisons,
but uses internal viewer descriptors and reflection. No reference code was
copied and no global compare hook was added in this task.

Developed on a local feature branch for integration into local master. No push,
release or change to the user's installed EDT was performed.
