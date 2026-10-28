# BSL write review in the EDT compare editor

Status: approved by the user on 2026-10-28. Implementation has not started.

Every actual BSL text change made through `write_module_source` must, by
default, be reviewed by a person in a nonmodal EDT editor tab. The person
accepts or rejects the complete proposal using buttons inside that tab.
A plugin preference can disable this review for subsequent requests.

## Agreed requirements

- Use the native Eclipse compare editor with a custom header containing
  `Apply` and `Reject` buttons.
- Both comparison sides are read-only. The proposed text cannot be edited.
- Reviewing a change must leave the rest of EDT usable.
- Review is enabled by default and applies to every write, rather than a
  subset selected by the agent.
- The user can disable review in plugin preferences.

The implementation choices below are proposals made by the designer. They
are not additional preferences already expressed by the user.

## Scope

The first version covers every supported `write_module_source` mode:
`replace`, `append`, `searchReplace`, `replaceMethod`, `insertBefore`, and
`insertAfter`, including creation of a previously absent BSL file.

Metadata mutation tools, quick fixes, refactorings, and edits made directly
by the developer are outside this first version. This is not a claim that
all possible BSL changes anywhere in EDT are intercepted.

Editable proposals, selecting individual diff hunks, changing unsaved editor
buffers, automatic rebasing, persistent recovery across EDT restarts, and
workspace/project YAML policy files are outside scope.

## Approaches

| Approach | Tradeoff | Decision |
| --- | --- | --- |
| Native Eclipse compare editor with a custom header | Reuses text comparison, synchronized scrolling, and difference navigation; requires proposal lifecycle integration | Selected by the user |
| Custom editor hosting a diff viewer | More layout freedom, with additional editor lifecycle and action integration | Not selected |

Use public `org.eclipse.compare` APIs. A `CompareEditorInput` subclass creates
a header and hosts the normal comparison controls below it. Open it with
`CompareUI.openCompareEditor`; do not open a compare dialog or a separate
confirmation dialog. Add the platform bundle/package dependency explicitly.
Generic text comparison is sufficient; BSL-specific highlighting is optional
and must not become a prerequisite.

## User experience

The header identifies the project and module, shows the current review
status, and contains text buttons `Apply` and `Reject`. Left is `Current
source`, right is `Proposed source`. The comparison shows the complete
resulting module after the tool's normal source normalization.

For a new module, show an empty left side and a `New file` indication. The
parent folders and file must not be created before acceptance.

While awaiting a decision, both buttons are enabled. `Apply` claims the
proposal exactly once, disables both buttons, and schedules the commit.
After successful commit the header says `Applied`; the comparison remains
available until the user closes it. Failure or a stale base is shown in the
same header, without a modal message box.

`Reject` completes an awaiting proposal without writing and leaves a
read-only `Rejected` view. Closing an awaiting tab also rejects it. Closing
a tab after `Apply` has claimed the operation does not assert cancellation
of an in-flight write. `Ctrl+S`, merge-copy actions, and editor save-on-close
must never accept a proposal or change the module.

No actual byte change means no review tab and an immediate `noChange`
response. It must not run a new metadata mutation as a hidden side effect.

## Preference

Add `Review BSL changes before applying` to the plugin General settings,
near the existing consent settings. Its default is `true`, including on
upgrade when no explicit value exists. Use the existing preference
constants, initializer, and General tab load/save/default conventions.
No restart is required.

This setting is independent of the destructive-operation consent level,
per-tool allow lists, and session-wide destructive consent. None of those
settings bypass BSL review. Do not add a `confirm`, `skipReview`, or equivalent
parameter to the write tool, and do not expose this preference through an
agent-writable configuration endpoint.

Read the preference once when admitting a write. Disabling it affects new
requests only; pending proposals continue to require their own decisions.
When disabled, preserve the existing direct-write behavior and its guards.
When enabled but no usable workbench UI exists, return an actionable refusal
without writing. Unattended tests must explicitly disable this preference
in their isolated test workspace.

## Preparation and commit

Keep existing parameter validation, bilingual project/module resolution,
vendor-support checks, source limits, normalization, syntax checks, BOM,
and line-ending preservation. Separate preparing a result from committing
that prepared result; approval must not re-execute the original request
against potentially different source text.

Preparation captures one coherent source snapshot, the exact proposed
output bytes, base existence and raw-byte fingerprint, canonical file
identity, project lifecycle identity, and the minimum immutable information
needed for the final response. The existing normalized `contentHash` /
`expectedHash` contract remains unchanged. The review uses an additional
internal fingerprint so disk-format changes cannot silently replace the
reviewed base. Do not keep live BM/EMF objects or transactions over the wait.

First-version handling of unsaved changes is deliberately conservative:
if the target has an unsaved editor/document buffer, refuse preparation or
application and ask the developer to save or discard those edits first.
Do not save the developer's buffer implicitly. Buffer discovery must cover
the target across workbench windows and Eclipse text-file buffers.

Before writing an accepted proposal:

1. Verify that it belongs to the current server generation and has not
   expired or already completed.
2. Re-resolve the same open project and canonical target. A closed/deleted
   and recreated project is a different identity, even with the same name.
3. Recheck file containment, editability/vendor support, unsaved-buffer
   state, file existence, and the captured base fingerprint.
4. Serialize comparison and write under the target's workspace modify rule
   for every reviewed mode, including file creation. An unexpected existing
   file makes a new-file proposal stale.
5. Write exactly the accepted result and run the existing adopted-module
   state/export follow-up once, using freshly resolved model context.

No workspace scheduling rule, BM transaction, SWT wait, or worker thread is
held during human review. The final unsaved-buffer check must be ordered
with the file write so a developer edit cannot arrive in a check/write gap.
Use a bounded UI/file-rule handoff or equivalent verified Eclipse mechanism;
never wait synchronously for SWT while holding a rule SWT may need. Model
export and long follow-up work run off the UI thread.

External processes that bypass Eclipse scheduling rules remain a limitation
of `IFile.setContents`; this feature must not promise an OS-level atomic
compare-and-swap against arbitrary external writers.

A changed base is terminal `stale`, with no automatic merge or recomputation.
The caller reads the new source and submits a new proposal. Preserve the
existing distinction between refusal before mutation, an unknown write
outcome, and a follow-up failure after the text has already been written.

## Proposal lifecycle

Use a small dedicated in-memory review registry. Existing `BackgroundJobs`
submits executable work to a bounded worker pool; do not occupy its workers
with human decision waits or redesign that shared scheduler for this feature.
Only accepted proposals schedule executable Eclipse jobs.

| State | Meaning |
| --- | --- |
| `awaitingApproval` | Tab is available and no mutation has begun |
| `applying` | Apply won the decision race; commit/follow-up is in progress |
| `applied` | Commit and required follow-up completed |
| `rejected` | User rejected or closed an awaiting tab |
| `stale` | The accepted proposal no longer matches its target/base |
| `expired` | Human decision deadline elapsed without acceptance |
| `cancelled` | Pending review was withdrawn by server/project lifecycle |
| `failed` | UI creation, commit, or required follow-up failed |

All transitions out of `awaitingApproval` are atomic. Repeated clicks,
tab disposal, expiry, and shutdown must have one winner. An Apply racing
expiry must check the deadline at admission, not rely on timer punctuality.

Designer defaults: at most 16 active proposals, at most one per canonical
module, a 30-minute decision deadline, and at most 100 retained terminal
statuses. Bound retained source/output payloads to 16 MiB in total, measured
in UTF-8 bytes, in addition to existing source-size limits. Strip text and
commit callbacks on termination; retain only status/result metadata. Refuse
new admission at a bound without evicting active proposals.

A matching repeat of an awaiting request returns the existing review ID
and focuses its tab. A different request for the same module returns a
`reviewPending` refusal with that ID. This is pending-request deduplication,
not a durable exactly-once guarantee across completed requests or restarts.
After a connection loss the caller queries the returned ID when known;
after an uncertain completed write it rereads source instead of blindly
resubmitting an append operation.

Server stop cancels awaiting proposals, invalidates their UI actions, and
closes pending tabs best-effort. Restart creates a new generation. Work that
has crossed its mutation boundary must finish/report its real outcome;
shutdown must not relabel an applied or uncertain mutation as unapplied.
Plugin unload drops the registry; IDs do not survive EDT restart. Project
closure/removal invalidates pending reviews for that project.

## MCP contract

When review is enabled, a successful changing `write_module_source` call
returns promptly after the nonmodal tab is created, with JSON containing
`reviewId`, `status: awaitingApproval`, `applied: false`, `projectName`,
`modulePath`, and the next polling tool. UI creation failure is a refusal;
it must never fall through to direct writing.

Add the read-only `get_write_review_status` tool with required `reviewId`.
It returns JSON because its ID and state are consumed by clients. Its result
contains identity, status, timestamps/deadline, and mutation outcome. Terminal
application responses include the existing write result or `ToolResult`
error information, including `mutationCommitted` or unknown-outcome markers
when applicable. It does not include the source text.

Unknown or evicted IDs return the normal actionable `ToolResult.error` and
must not be interpreted as rejection. Polling is immediate, without a
long-poll wait; recommend a few seconds between calls. Polling never applies,
rejects, extends the deadline, or recreates a proposal. No MCP acceptance
tool is added: acceptance is a UI action.

Keep the historical Markdown direct-write response when review is disabled.
For review-enabled requests, return a consistent JSON envelope, including
the immediate `noChange` case. Response-type selection must be bound to the
same admission decision as execution, rather than rereading a preference
after it changes. Verify this through the actual protocol handler; adapt
the result plumbing if per-call `getResponseType(params)` cannot express
that safely.

Update tool guides, toolsets/presets, generated docs, the schema golden,
and client workflows so awaiting approval is never described as a completed
write. The new status tool must remain available in read-only presets.

## Integration boundaries

- `WriteModuleSourceTool`: preserve request preparation and write helpers;
  insert review after validation/computation and before any mutation.
- `ui/`: new compare input/header and document-state probing as needed.
- `utils/`: review registry, immutable prepared-write data, and commit
  coordination. Names and exact class split are left to the implementer;
  do not put non-tool classes in `tools/impl/`.
- `preferences/`: add the default-on boolean and visible control. Keep
  existing destructive-consent behavior separate.
- `tools/impl/`: add the status tool, with normal registration and guides.
- Server/plugin lifecycle: cancel pending review state on stop/unload and
  prevent stale tabs from committing after restart.

Reuse `ProjectContext`, `BslModuleUtils`, `ContentHash`, `BmTransactions`,
`ToolResult`, and `AdoptedModuleState` where applicable. No vendor code is
copied from Effector. Its decompiled code was research material only.

## Acceptance and validation

Unit tests must exercise decision races, repeated Apply, reject/close,
expiry, stale bases for every write mode, creation races, preference changes,
pending deduplication, resource limits, lifecycle invalidation, and honest
mutation outcomes. Test unsaved documents both before preview and before
commit, including documents opened in another workbench window. Preserve
English/Russian BSL and metadata resolution, BOM, and CRLF/LF cases.

Live EDT tests must show an interactive workbench while the diff is open;
read-only sides and disabled merge/save actions; working header buttons;
zero disk/model mutation before acceptance; exactly one accepted write;
and no implicit saving of human edits. Include headless refusal with review
enabled and explicit opt-out in existing unattended suites.

Run the repository's build/unit, live redeploy, golden/e2e, conformance, and
EDT-log review gates before claiming implementation is ready. This document
does not claim those implementation checks have been run.

## References

- Current write flow: `WriteModuleSourceTool.execute`, `writeModule`, and
  `finishWrite` in the server bundle.
- Existing General tab and preference initialization conventions in
  `preferences/GeneralTab.java` and `PreferenceInitializer.java`.
- Existing executable background-work model in `utils/BackgroundJobs.java`.
- [Eclipse CompareEditorInput API](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/compare/CompareEditorInput.html).
