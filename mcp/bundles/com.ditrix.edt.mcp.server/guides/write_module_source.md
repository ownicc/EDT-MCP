Writes BSL source to a single 1C metadata object module (a `.bsl` file under `src/`). Six edit modes, a mandatory BSL syntax check, and lost-update guards.

## Preview without applying

Set `preview:true` to prepare the same edit and return JSON without writing source,
changing metadata, registering a review or opening an editor. This works whether
human review is enabled or disabled. The usual target, vendor, dirty-buffer,
expected-hash, mode and syntax checks still apply; `skipSyntaxCheck:true` is
reported as `syntaxChecked:false`.

The result includes `wouldChange` (exact output-byte or file-presence change),
the current module's normalized `contentHash`, `proposedHash`, line counts,
`normalizedCharacters` when normalization occurred, and a bounded `diff` with
Current/Proposed hunk coordinates. `applied` and `mutationCommitted` are false.
The diff is limited to 200 formatted lines and 65536 bytes; its text explicitly
reports truncation or an input limit. A diff limit does not make `wouldChange`
an equality claim. Byte-only changes can have equal visible text.

Do not combine `preview` with `requestId`: previews neither reserve nor replay
operation receipts and return no executable patch token. To request the actual
write, submit the edit again without `preview`, using a fresh `requestId` if
needed. All guards and review settings are checked again; a preview does not
authorize a later write or guarantee that the base remains current.

Example: `{projectName, modulePath, mode:"searchReplace", oldSource:"Old();",
source:"New();", expectedHash:"<hash from a disk read>", preview:true}`.

## Human review and completion

By default, a changing call opens a nonmodal read-only comparison in EDT and returns JSON with `reviewId`, `status`, `applied: false`, and the decision deadline. No source or metadata is written before the person presses **Apply** in the tab header. **Reject** or closing an awaiting tab writes nothing. Both comparison sides are read-only; saving or merge-copy shortcuts cannot accept a proposal.

Poll `get_write_review_status(reviewId)` about every three seconds until the status is terminal. Awaiting approval is not a completed write. The decision deadline is 30 minutes. A changed base becomes `stale`; read it again and submit a new proposal. Save or discard unsaved target edits before submitting or applying. Server/project closure cancels work that has not entered mutation; in-memory review IDs may be lost on plugin restart. An optional requestId retains a bounded operation receipt as described below.

When `mutationCommitted: true`, source was written even if a later metadata/export step failed. An unknown mutation outcome requires reading source before retrying. Never blindly resend an append after a lost connection or uncertain result.

The human can turn off **Review BSL changes before applying** in the plugin General preferences. This affects new calls only, preserves the historical immediate Markdown result, and leaves pending proposals awaiting their own decisions. Destructive-consent allowances and `skipSyntaxCheck` do not bypass review; no MCP approval or preference-bypass parameter exists. Without a workbench, review-enabled changing writes are refused. Byte-identical proposals return JSON `noChange` without metadata follow-up.

## Request receipts and replay

Supply an optional `requestId` containing 1 to 128 characters, unique for each operation in this workspace. Retrying that ID with the same effective arguments (including defaulted mode/boolean settings and the resolved module selector) returns its existing receipt without preparing or executing another write. Changing those arguments produces `requestIdConflict`. A request ID is never a way to bypass a changed hash or a human decision.

Admission is written and flushed to the plugin's workspace state directory before execution. Receipts contain IDs, an argument digest, timestamps and a bounded result; source arguments and proposal payloads are not persisted. Calls using `requestId` return a JSON envelope with `requestId`, `retainedUntil`, `status`, mutation outcome fields, and `resultMarkdown` for a direct write. Calls without it retain the existing response format. A large result may be summarized with `resultTruncated: true` while preserving the mutation outcome.

A concurrent duplicate reports `inProgress` with an unknown mutation outcome until the first call reports a result. Review receipts include `reviewId` and follow its latest decision, including cancellation or completion after the original call returned. Only the human can Apply in EDT. Poll `get_write_review_status` with exactly one of `requestId` or `reviewId`; requestId lookup does not require resending source arguments.

After plugin/EDT restart, a completed receipt remains replayable. An interrupted operation or pending review becomes `status: unknown`, `recovery: interrupted`, `mutationOutcomeUnknown: true`; it is never automatically retried or recreated. Reread source and reconcile the operation before deciding whether a genuinely new operation is needed. Receipts do not promise exactly-once mutation across process/power failure or failed storage. If `receiptPersistenceFailed: true`, the returned outcome is the strongest fact observed in this process; restart recovery may report unknown. Admission failure starts no write.

Retention is seven days from admission, with at most 1000 receipts of at most 64 KiB each. An unexpired receipt is never evicted for capacity: new admission refuses when full. A known expired ID returns `requestIdExpired`; older files may be removed when admitting another operation. There is no safe replay guarantee after `retainedUntil`. **Never recycle a requestId**, including after expiry, project deletion, cancellation, or a workspace restart. Copying a project into another workspace does not copy these workspace-local receipts.

## When to use

- Editing existing BSL: prefer `searchReplace` (the default) — surgical and safe.
- Replacing one complete procedure/function: `replaceMethod`.
- Adding one complete procedure/function next to an existing anchor: `insertBefore` or `insertAfter`.
- Adding one complete method to an existing empty region: `insertIntoRegion`.
- Rewriting or creating a whole module: `replace` (the only mode that can create a new file).
- Adding code at the end of a module: `append`.

## Targeting the module (exclusive OR)

Pass EXACTLY ONE of:
- `modulePath` — direct `src/`-relative path, e.g. `Documents/MyDoc/ObjectModule.bsl` or `CommonModules/MyModule/Module.bsl`.
- `objectName` + (optional) `moduleType` — resolves the path for you.

Passing both is rejected; passing neither is rejected. `moduleType` is meaningful ONLY with `objectName` — combined with `modulePath` it is rejected, not silently ignored.

## Parameter details

| Param | When | Notes |
|---|---|---|
| `projectName` | always | EDT project name. |
| `modulePath` | XOR objectName | `src/`-relative `.bsl` path; no `..`. |
| `objectName` | XOR modulePath | `Type.Name`; see Bilingual. |
| `moduleType` | with objectName | default `ObjectModule`. |
| `source` | unless edits is supplied | the BSL to write (max 500000 chars); exactly one complete method for a method-targeted mode. |
| `oldSource` | mode=searchReplace | must match exactly once. |
| `mode` | optional | `searchReplace` (default), `replace`, `append`, `replaceMethod`, `insertBefore`, `insertAfter`, `insertIntoRegion`, `replaceMethods` (implied by edits). |
| `edits` | mode=replaceMethods | 1–50 objects: `{methodName, source}`. One combined proposal; max 500000 raw source characters in total. |
| `methodName` | replaceMethod/insertBefore/insertAfter | existing unambiguous method anchor; REQUIRED. |
| `regionName` | insertIntoRegion only | existing uniquely named empty region; REQUIRED, case-insensitive. |
| `formName` | moduleType=FormModule | except CommonForm. |
| `commandName` | moduleType=CommandModule | except CommonCommand. |
| `skipSyntaxCheck` | optional | default false. |
| `expectedSource` | mode=replace | lost-update guard. |
| `overwrite` | mode=replace | force without expectedSource. |
| `requestId` | optional | workspace-unique operation ID; JSON receipt replay and recovery, seven-day retention; never reuse. |
| `expectedHash` | any mode | whole-module guard; REQUIRED for insertIntoRegion and method-targeted modes unless replaceMethod uses expectedMethodHash. |
| `expectedMethodHash` | replaceMethod only | methodHash from read_method_source; guards the exact replaced method span. Not for edits or insert modes. |

## moduleType to path

`ObjectModule` (default), `ManagerModule`, `RecordSetModule`, `Module` resolve to `<Dir>/<Name>/<moduleType>.bsl`. `FormModule` resolves to `<Dir>/<Name>/Forms/<formName>/Module.bsl` and REQUIRES `formName` — except CommonForm, which has no per-form name and resolves to `CommonForms/<Name>/Module.bsl`. `CommandModule` resolves to `<Dir>/<Name>/Commands/<commandName>/CommandModule.bsl` and REQUIRES `commandName` — except CommonCommand, which resolves to `CommonCommands/<Name>/CommandModule.bsl`.

## Modes

- `searchReplace` (default): finds `oldSource` and replaces it with `source`. `oldSource` is REQUIRED and must match EXACTLY ONE location — zero matches or multiple matches are rejected with a steer to read again / give a larger fragment. The match runs on the raw file content (trailing newline preserved), so a fragment ending at EOF including its final newline is found. The file must already exist.
- `replace`: replaces the entire file. The ONLY mode that can CREATE a new module (creates parent folders). Over an EXISTING module it is guarded (see Lost-update guards).
- `append`: adds `source` to the end. The file must already exist.
- `replaceMethod`: replaces the complete `methodName` definition. Its span includes the contiguous `//` documentation and `&...` annotations/directives immediately above the declaration. `source` must declare exactly one complete procedure/function with the same name AND the same kind; a rename is rejected, and so is turning a Function into a Procedure or back - callers of a Function consume its return value, and the block-balance check cannot see the difference.
- `insertBefore`: inserts the one complete method in `source` before the anchor's documentation/annotations, never between an annotation and its declaration - a blank line between them does not detach the directive.
- `insertAfter`: inserts the one complete method in `source` after the anchor's complete terminator.

Insert modes collapse edge/insertion-point blank lines to one separator on each side, without padding module edges. Method bodies, documentation/directives and preprocessor lines stay unchanged. `replaceMethod` does not normalize spacing.

For all three method-targeted modes, `methodName` is REQUIRED. Insert modes require `expectedHash`; `replaceMethod` accepts `expectedHash` or `expectedMethodHash`. The anchor must resolve to exactly one declaration; duplicate declarations in preprocessor branches are rejected as ambiguous. For insert modes, the incoming method name must not already exist anywhere in the module, so repeating an insert is refused instead of creating a duplicate.

### Inserting into an empty region

`insertIntoRegion` requires `regionName`, `expectedHash` and one complete method
in `source`. It inserts before the matching region end. Existing comments stay.
The RU/EN region name must be unique across the module, including conditional
branches. A uniquely named empty inner region is allowed; the target's contents
must be only blank lines or full-line comments. Missing, malformed/unbalanced,
nonempty or inside-method regions are refused. No region is created. A duplicate
method name anywhere in the module is refused, ignoring case.

Do not supply `methodName`, `edits`, `oldSource`, `expectedSource`, `overwrite`
or `expectedMethodHash`. Preview/review and full-snapshot commit guards apply.
Example: `{projectName, modulePath, mode:"insertIntoRegion", regionName:"PublicAPI",
source:"<one complete new method>", expectedHash:"<contentHash>"}`.

### Guarding one method

For `replaceMethod`, pass `read_method_source.methodHash` as `expectedMethodHash`.
Omitting `expectedHash` permits unrelated edits before preparation, preserved
from the current saved module. Both tokens supplied means both must match;
stale/blank module tokens are never ignored. The method token covers contiguous
documentation/directives, declaration, body and terminator, ignoring BOM and
LF/CRLF. Changed, removed, ambiguous or unaddressable targets refuse. It guards
text, not dependencies or surrounding preprocessor context; use the module
token for those. Preview shares the guard. ANY change after preparation rejects
the full byte snapshot. Dirty-buffer/vendor/kind/name/human-review checks remain;
unsaved changes cannot be overwritten. Batch/insert modes require the module
token and reject `expectedMethodHash`.

The method parser supports RU/EN Procedure/Function and matching terminators.
Terminators must have a keyword boundary and own their line: `EndProcedureResult`
and `EndProcedure; ModuleValue = Call();` do not close a method. An incomplete
method never borrows the next terminator. Unsupported shapes refuse the module
with a line/fix: split declaration keyword/name/opening parenthesis; unclosed
parameters (ordinary wrapped parameters are supported); block-keyword method
names; terminators sharing a line with code; declarations after other code;
pragmas sharing a declaration line or having multiline/unclosed arguments; and
whole methods on one line.

## Lost-update guards

Concurrent edits between your read and write are caught by:
- `expectedHash` (ANY mode; REQUIRED for insertIntoRegion and method-targeted modes except a `replaceMethod` guarded by `expectedMethodHash`): pass the opaque `contentHash` from your last `read_module_source` / `read_method_source`. If the module changed, the write is rejected. Omit it when creating a new module, because there is no existing content to match.
- `expectedSource` (mode=replace): pass the exact content you last read. Mismatch is rejected.
- `overwrite=true` (mode=replace): force the overwrite with no content check.
A bare `replace` over an existing module with none of these is rejected and steers you toward expectedSource / overwrite / searchReplace. A matching `expectedHash` already satisfies the replace precondition. All comparisons are `\n`-normalized, so a CRLF/LF-only difference is not a spurious mismatch.

## BSL syntax check

Before writing, the resulting content is checked for balanced block keywords (Procedure/EndProcedure, Function/EndFunction, If/EndIf, While/EndDo, For/EndDo, Try/EndTry). On error the write is BLOCKED and the errors are returned. Pass `skipSyntaxCheck=true` to force.

## Bilingual (ru/en)

`objectName` resolves by the object's programmatic `Name`, NOT by its synonym. Only the TYPE token may be bilingual: the English `Document.MyDoc` and its Russian equivalent (the Cyrillic type token plus the SAME programmatic Name) resolve to the same module. Resolve by Name, never by synonym.

## Extension method interception (annotations)

In a configuration EXTENSION you intercept a base module METHOD by writing an annotated procedure. This is plain BSL, so `write_module_source` handles it directly - the annotation passes through verbatim (the syntax check only balances block keywords, it does not touch annotations). This is the METHOD counterpart of the form-EVENT interception that `create_metadata`'s `callType` produces; methods use annotations, events use `form:EventHandlerExtension`.

Annotation over the extending procedure, naming the BASE method in quotes:
- `&Before("BaseMethod")` - run before the base method.
- `&After("BaseMethod")` - run after the base method.
- `&Around("BaseMethod")` - run instead of / wrapping the base method (1C "Вместо"; can call `ПродолжитьВызов`).
- `&ChangeAndValidate("BaseMethod")` - 1C "ИзменениеИКонтроль".

The keywords serialize in ENGLISH on disk (`&Before`/`&After`/`&Around`/`&ChangeAndValidate`); the Russian `&Перед`/`&После`/`&Вместо`/`&ИзменениеИКонтроль` are editor display aliases for the same annotations. Note: the METHOD "Вместо" annotation is `&Around` (a method wrapper), which is distinct from the form-EVENT "Instead" call type (`create_metadata` `callType=Instead`, serialized as the `Override` call type) - methods and events use different mechanisms.

Preconditions for a clean `get_project_errors`:
1. The host extension module must EXIST - adopt the base object/form into the extension first (`adopt_metadata_object`), or create the extension common module via `create_metadata`. `write_module_source` does not adopt the object; writing the module of an adopted object also records that module's state `Extended` when it is `None` (see Modules of adopted objects). `mode=replace` can create a missing file, `searchReplace`/`append` need it to exist.
2. The BASE method must exist in the parent configuration with a matching signature.
3. Run `get_project_errors` after writing to confirm the extension method resolved.

## Modules of adopted objects

In a configuration extension, an ADOPTED object's `.mdo` can record a state for each of its modules - `<module>` for a common module, `<objectModule>`, `<managerModule>`, `<recordSetModule>`, `<valueManagerModule>`, `<commandModule>`, `<sessionModule>` and so on for the others - and has that line only once the state is set. When code is added to an adopted module in EDT's UI, EDT records `Extended` there, e.g. `<module>Extended</module>`. EDT's own listener also switches the state from `None` to `Extended` after a module file is written, but only in its model, a few seconds later, and it never saves the `.mdo`.

This tool records the state at once: after writing such a module it switches the state from `None` to `Extended` with EDT's own call, exports the owner's `.mdo`, and reads that `.mdo` back until it carries the state line, for up to 60s counted from the export request - EDT's export queue drains even when EDT fails to write the file, so only the file confirms the export. The export request itself is EDT's own call, the one `create_metadata`, `modify_metadata` and `delete_metadata` make too: it counts toward the 60s but is not cut short, so while EDT keeps it waiting the call waits too. While an earlier `.mdo` read of the project is still running or waiting to run, no other starts - for up to 3 minutes, after which one more may - and the `.mdo` counts as not read back. The response's frontmatter then carries `moduleExtensionState`:
- `switchedToExtended` - the state was `None` and is now `Extended`; the body says so.
- `alreadyExtended` - the state already was `Extended` (EDT's listener may have set it first); the `.mdo` is exported again to match the model and read back the same way. Which of the two values a write reports is a race with that listener, so an export that is not confirmed fails the write either way (below).
- `keptChecked` or `keptNotify` - the state is `Checked` or `Notify`; like EDT's own listener, this tool switches only `None`, so it is left exactly as it is, and the body says so.
- `noExtensionBlock` - the adopted object has no extension block in its `.mdo` (for the extension root, `Configuration.mdo`), so the state cannot be recorded; the body names the object. A root created by EDT's own new-extension wizard, or by `create_project` (which adopts the base's root as that wizard does), has the block; a root made by an earlier version of `create_project` has none, and the body says how to get one.

Each of these is a success: the module text is written. The state is never switched back - emptying the module keeps `Extended`, as in EDT - `Checked` or `Notify` is never overwritten, and a missing extension block is never created. Modules of the extension's own objects and of a base configuration are untouched: their responses carry no key - except for the transient error below, while EDT's model of the extension is not available.

When the step cannot finish, the call returns an error with `mutationCommitted:true`: the module text IS written - do not re-send the original edit. The error names the cause and the fix: EDT's model of the extension was not available - a transient window in which an adopted object cannot be told from an own one, so this error can come for any module of the extension, its own objects included; the step failed; or the state is `Extended` in EDT's model - switched or already - but its `.mdo` export was not confirmed: the `.mdo`, read back, did not carry its line within 60s or could not be read back, or the export could not be scheduled and the `.mdo`, read once if time was left and no earlier read of the project's files was out, was not seen carrying it. The root's ordinary-application module is the exception: EDT does not list it among the properties an extension can extend, so none of these fails a write into it - the body notes it instead. To run the step again, call `write_module_source` on the same module with `mode=append` and an empty `source`: it rewrites the module's current text, line ends normalized (add `skipSyntaxCheck=true` if the module was written with it).

Not covered - these modules are written as text only, so set their state to `Extended` in EDT: the modules of an external data source's tables, cubes and dimension tables, and `Bot`, `IntegrationService` and `WebSocketClient` modules. A recalculation's record-set module has no extension state at all. A form module needs nothing: the form's own state (`<form>Extended</form>`) is set when the form is adopted.

The empty-append retry above is available when review is disabled. With review enabled, byte-identical output returns `noChange` without running the adopted-module step. Inspect the module and metadata in EDT before recovering from a failed export.

## Replace several methods in one review

Pass `edits` instead of top-level `source`. This implies `mode=replaceMethods`; supplying that mode explicitly is also accepted. Every entry contains exactly `methodName` and `source` (one complete Procedure/Function, including its desired contiguous documentation/annotations). Names must be distinct case-insensitively; replacements keep each method's name and kind. One entry cannot insert, delete or rename a method.

One whole-module `expectedHash` is required. All targets resolve against the same original text; the server builds the complete proposal and syntax-checks it once. A bad entry rejects the entire batch before a review or source write. With review enabled there is one diff and one human Apply/Reject for all replacements. With review disabled the existing guarded write commits the combined text once. The usual dirty-buffer, vendor and late-hash guards still apply.

Do not combine `edits` with top-level `source`, `methodName`, `oldSource`, `expectedSource`, `overwrite` (even false), or another mode. Malformed/non-object entries, unknown entry fields and duplicate names refuse; no entry is silently skipped. Limits: 1–50 replacements and 500000 raw source characters in total. `skipSyntaxCheck` and `normalizeInvalidCharacters` apply to the entire batch; normalization touches only incoming fragments. Method replacement errors include a zero-based `editIndex`; argument-validation messages identify the offending `edits[i]` or field.

Optional `requestId` covers the complete ordered batch. A matching retained request replays its receipt; changing any entry or order conflicts. Retention/restart limits are the same as for a single edit. Do not send a whole module merely to change three methods:

```json
{
  "projectName": "MyProj",
  "modulePath": "CommonModules/MyModule/Module.bsl",
  "expectedHash": "<contentHash from a disk read>",
  "requestId": "<new unique ID>",
  "edits": [
    {"methodName": "Calculate", "source": "Function Calculate() Export\n    Return 2;\nEndFunction"},
    {"methodName": "Validate", "source": "Procedure Validate() Export\n    Check();\nEndProcedure"},
    {"methodName": "Report", "source": "Procedure Report() Export\n    Show();\nEndProcedure"}
  ]
}
```

## Examples

Surgical edit (default mode):
```
{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl",
  "oldSource": "Return 1;", "source": "Return 2;" }
```

Form module via objectName:
```
{ "projectName": "MyProj", "objectName": "Document.MyDoc",
  "moduleType": "FormModule", "formName": "ItemForm",
  "mode": "replace", "source": "...", "overwrite": true }
```

Extension method interception (append an annotated procedure to an adopted extension common module):
```
{ "projectName": "MyExt", "objectName": "CommonModule.Calc", "moduleType": "Module",
  "mode": "append",
  "source": "\n&After(\"Add\")\nProcedure ext_AddAfter(A, B, Result) Export\n\t// runs after CommonModule.Calc.Add\nEndProcedure\n" }
```

Replace one method using the revision token returned by a read:
```
{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl",
  "mode": "replaceMethod", "methodName": "Calculate",
  "expectedHash": "<contentHash from read_method_source>",
  "source": "Function Calculate() Export\n    Return 2;\nEndFunction\n" }
```

Insert a new method before an existing anchor:
```
{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl",
  "mode": "insertBefore", "methodName": "Calculate",
  "expectedHash": "<contentHash from read_module_source>",
  "source": "Procedure Prepare() Export\nEndProcedure\n" }
```

## Gotchas

- Only `.bsl` files; `modulePath` may not contain `..`.
- `searchReplace`/`append` and all method-targeted modes need an EXISTING file; only `replace` creates one.
- New BSL files are written with a UTF-8 BOM; existing files keep their BOM state.
- `source` is `\r\n`->`\n` normalized and the file always ends with a newline.
- In an extension, writing the module of an ADOPTED object also switches that module's state from `None` to `Extended` and exports the object's `.mdo`; `Checked`, `Notify` and a missing extension block are left as they are (see Modules of adopted objects).

## Addressing the module

`modulePath` and the `objectName` + `moduleType` pair are mutually exclusive - give one shape or the other, never both.

## Vendor support
A configuration on vendor support can lock objects. Writing a module is refused when the object that owns it (a common module, a catalog for its object module, a form for its form module; the configuration for its own modules) or the module itself is locked by its support rule. The file is not touched. The module must belong to `projectName`: an absolute `modulePath` that resolves into another project is refused. The error names the object and says nothing was changed. The way out is a configuration extension (adopt the object with adopt_metadata_object and change the adopted copy) or the user allowing changes in EDT's support settings; this server never changes support settings. If EDT's support check cannot be answered, the write is refused too. A configuration extension and an external data processor/report project are never under vendor support: EDT applies support only to a configuration's own (native) root, never inside an extension, which is why an extension is the way to change a supported configuration.
