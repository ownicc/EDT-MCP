# Open BSL modules in EDT

The user approved a separate `open_in_editor` MCP tool for BSL modules only, implemented in a Worktrunk branch. Required inputs: `projectName`, `modulePath`. Optional navigation: `methodName`, or 1-based `line` and optional `column`. Without navigation, activate the editor and retain its selection. Reject conflicting selectors and invalid coordinates.

Use existing workspace module resolution and Eclipse `IDE.openEditor`; resolve method positions from the actual editor document, including unsaved edits, with the shared BSL declaration scanner. Never save or modify source. Reject missing/ambiguous methods and non-BSL or foreign-project targets. Execute editor operations on the UI thread, report actual completion, and bound waiting from MCP threads.

A separate tool keeps explicit UI navigation independent of semantic `go_to_definition`. Extending that reader with a UI flag would combine different contracts. New metadata/form designer routing and external files are outside this change. No dependencies or shared editor framework are needed.

Classify UI activation as a non-destructive mutation; disable it in read-only presets. Place it in the code toolset and BSL preference group. Add unit coverage of real document navigation and validation, an E2E module, guide and catalogue entry. Verify the full Java suite and build. Live EDT verification depends on an available test workbench; report it explicitly if unavailable.

## Validation

Real workbench integration can be run in a disposable graphical environment:
`xvfb-run -a env JAVA_TOOL_OPTIONS=-Dedt.mcp.uiTests=true mvn -f mcp/pom.xml verify -Dtest=OpenInEditorWorkbenchTest -Dsurefire.failIfNoSpecifiedTests=false`.
The opt-in test opens a module, edits only its live document, verifies reuse, method and coordinate selection from UI/background callers, then closes its own workbench and removes its fixture. It is skipped in the ordinary headless suite.

Review found an incomplete declaration census when split/inline declarations are present. The tool now uses the scanner's existing `unaddressable` guard and directs such navigation to explicit line/column rather than choosing a possibly ambiguous method. A real Document regression failed before the fix and passed after it. Saved read-only profiles gain this new UI-mutating tool in migration 18; custom/development/all-tools profiles retain their choices.
