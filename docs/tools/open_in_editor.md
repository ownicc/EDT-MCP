# open_in_editor

Open or activate a BSL module in EDT, optionally at a method or line/column. Preserves unsaved edits. Parameters and examples: get_tool_guide('open_in_editor').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name |
| modulePath | — | string | BSL path from src/; exactly one of modulePath or objectName |
| objectName | — | string | Type.Name; TYPE may be Russian/English, name is a programmatic identifier, not a synonym |
| moduleType | — | string | With objectName: ObjectModule, ManagerModule, FormModule, CommandModule, RecordSetModule or Module; defaults by type |
| formName | — | string | With objectName and FormModule: form identifier; not required for CommonForm |
| commandName | — | string | With objectName and CommandModule: command identifier; not required for CommonCommand |
| methodName | — | string | Case-insensitive method name; cannot be combined with line/column |
| line | — | integer | 1-based line in the current editor document |
| column | — | integer | 1-based column; requires line; default 1 |

## Guide
Open or activate an existing BSL module in the EDT workbench. Optional navigation uses the current editor document, including unsaved edits. The tool does not change or save source.

## Parameters
- `projectName` is required. Pass exactly one of `modulePath` or `objectName`.
- `modulePath` is a path from `src/`, for example `CommonModules/Calc/Module.bsl` or the configuration module `Configuration/ManagedApplicationModule.bsl`. Only workspace BSL modules in the selected project are supported; external/absolute paths and metadata/form designers are excluded.
- `objectName` uses the existing `write_module_source` addressing convention: `Type.Name`. Only the TYPE token may be Russian or English (`CommonModule.Calc` / `ОбщийМодуль.Calc`); the object name is a programmatic identifier, not a synonym.
- Optional `moduleType` with `objectName`: `ObjectModule`, `ManagerModule`, `FormModule`, `CommandModule`, `RecordSetModule`, or `Module`. The default is `Module` for CommonModule/CommonForm/WebService/HTTPService, `CommandModule` for CommonCommand, and `ObjectModule` for other types. Use `modulePath` for configuration modules.
- `FormModule` requires `formName` except for CommonForm. `CommandModule` requires `commandName` except for CommonCommand. These names are programmatic identifiers. `moduleType` cannot be combined with `modulePath`.
- `methodName` navigates to the declaration line, case-insensitively, for either Russian or English BSL. A missing or ambiguous declaration is refused. If the scanner cannot account for every declaration (for example a split header), use line/column navigation instead. Method lookup uses the same declaration scanner as the text-based BSL tools; it does not require a ready semantic model.
- Alternatively, `line` and optional `column` select a 1-based position. Column defaults to 1. The caret may be placed immediately after the last character of a line, but never on a different line through an oversized column.
- Do not combine `methodName` with `line` or `column`. A column without a line is refused.
- Without navigation arguments, activate the editor and retain its current selection.

## Examples
```json
{"projectName":"TestConfiguration","modulePath":"CommonModules/Calc/Module.bsl","methodName":"Add"}
```
```json
{"projectName":"TestConfiguration","modulePath":"CommonModules/Calc/Module.bsl","line":6,"column":2}
```

```json
{"projectName":"TestConfiguration","objectName":"ОбщийМодуль.Calc","methodName":"Add"}
```
```json
{"projectName":"TestConfiguration","objectName":"Document.Order","moduleType":"FormModule","formName":"ItemForm","line":1}
```
```json
{"projectName":"TestConfiguration","objectName":"Document.Order","moduleType":"CommandModule","commandName":"Print"}
```

## Result and side effects
Returns JSON with `success`, `opened`, canonical `projectName`/`modulePath`, and `line`/`column` when navigation was requested. The existing editor is reused. Opening a previously closed module creates a tab; selecting a missing method or invalid position can therefore fail with `opened:true`. No source is modified and dirty editors are never saved or discarded.

The operation activates an EDT editor, so it has a UI side effect and is disabled by the Analysis Only and Code Review presets. It belongs to the `code` toolset. `go_to_definition` instead returns a semantic definition to the agent.

A running workbench and an active page are required. Calls from an MCP thread wait up to 10 seconds for UI completion. Work still queued when the wait is cancelled is skipped; a timeout/interruption during an operation already running can leave an editor open, so inspect EDT before retrying. The tool activates the tab; the operating system may independently restrict bringing the EDT window to the foreground.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
