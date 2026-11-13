A self-diagnosis snapshot of the running MCP server. Reach for it when something behaves oddly and you want the facts instead of guessing - especially a blank form screenshot or a JSON tool that came back as plain text.

## When to use
- `get_form_screenshot` / `get_form_layout_snapshot` returned blank - check the form-render modes here.
- A JSON-response tool gave you plain text - check `plainTextMode`.
- You want to confirm the port, protocol version, plugin/EDT version, or how many tools are enabled vs. registered (e.g. when progressive disclosure is hiding tools).
- Quick "is the server actually up and reachable" check.
- Explain why a named project's metadata, diagnostics, or local-runtime prerequisites are unavailable.

## Parameter details
- Omit both parameters for the existing server-only snapshot; no project or runtime is queried.
- `projectName`: explicit workspace project name. A blank name is an error.
- `operation`: `metadata` (default), `diagnostics`, or `runtime`; requires `projectName`.

## Project readiness

Example: `get_server_status(projectName="Demo", operation="diagnostics")` adds a `readiness` object without changing the server fields. The probe returns within two seconds of waiting for EDT. At most one probe is admitted; another call receives `unavailable` with reason `probe_busy` until the earlier platform call actually exits, even if that call ignored interruption after a timeout.

The three profiles check **project prerequisites**, not every argument or possible failure of a tool:

- `metadata`: EDT project context and derived-data readiness, BM model service, and registered BM model.
- `diagnostics`: the metadata prerequisites plus `IMarkerManager` and `ICheckRepository`. This does not rerun validation or require a platform installation.
- `runtime`: the metadata prerequisites plus availability of a registered local 1C:Enterprise installation supported by EDT on this OS. This only reads the installation registry. It does not search for, resolve, refresh, download, install, or execute a runtime. Remote execution environments are outside this profile.

`readiness` contains `projectName`, `operation`, `state`, `ready`, machine-readable `reason`, explanatory `message`, recovery `actions`, and `runtimeRequired`. States are `ready`, `building`, `missing`, `unsupported`, and `unavailable`. Missing project/installation is distinct from unsupported project/installation and unavailable services, closed projects, incomplete context registration, or a timed-out probe. The first blocker is reported; resolve it and retry to inspect subsequent prerequisites. `success:true` means the diagnostic returned a snapshot, including a snapshot with `ready:false`.

`ready:true` records only the observed prerequisites. State can change immediately afterward. Runtime availability does not verify project/platform version or component compatibility, application binding, database access, credentials, or licensing. Use `get_applications` and the intended tool's guide for operation-specific requirements. A missing platform does not block the `metadata` or `diagnostics` profiles.

The check is read-only: it does not save editors, build a project, change settings, connect to an infobase, or start a database update. Platform exception messages and installation paths are not included in the response.

## What you get

`transports.http` and `transports.ipc` report whether the HTTP and local IPC listeners are up. `running` is true when either listener is up. `port` retains the configured or last bound HTTP port; it indicates an active listener only when `transports.http` is true. An EDT started by stdio normally reports HTTP false and IPC true. No socket path or handshake secret is returned.
JSON with: `port`, `running`, `protocolVersion`, `pluginVersion`, `edtVersion`, `enabledTools` / `totalTools`, `plainTextMode`, `checksFolderConfigured`, `authEnabled`, and `formRenderFlags`. The latter contains `nativeFormLayoutRender` followed by `nativeFormBufferedLayoutRender`. Each flag always has `atStartup` (`on`, `off`, or `unknown`), sampled when the MCP plugin activates. The bundle is lazily activated, but EDT reads these modes once from system properties at class initialization. Each flag can also have `requested`, the raw system-property string currently set (omitted when unset). Changing a property later does not reconfigure EDT's renderer or its offscreen handler; changing the launch flag requires an EDT restart. On Windows, `get_form_screenshot` requires `-DnativeFormBufferedLayoutRender=true` after `-vmargs` in `1cedt.ini`; it is disabled by default because this flag makes EDT's own form designer show a grey rectangle instead of the form for the whole session (EDT behaviour). Linux and macOS already buffer native rendering. `get_form_layout_snapshot` does not require the buffered-render flag.

`enabledTools` counts preference-enabled registrations; headless capability filtering and progressive disclosure can further narrow `tools/list`.

`hostKind` is `WINDOWED` or `HEADLESS`, selected by the host application. A headless host has no workbench; its tool catalog excludes mandatory UI tools. Removing `-headless` from the client configuration changes the next cold start, not an already running host.

## Notes & gotchas
- Secrets are never exposed: you get only the `authEnabled` boolean (never the token) and `checksFolderConfigured` (never the folder path).
- `checksFolderConfigured: false` does **not** mean `get_check_description` is unavailable: the descriptions ship with the plugin. The flag reports only whether an operator configured a folder that overrides them.
- Judge how EDT was launched by `atStartup`: it is the launch state. `requested` is the raw system property as currently set; nothing in this plugin writes it.
- For blank or gray screenshots, `nativeFormBufferedLayoutRender.atStartup: on` does not prove EDT could create its offscreen image. On Windows, buffered mode also switches the human designer to that image; an unusable buffer leaves the designer gray. Check the EDT log for `AccessDeniedException` on `dtgraphics-form.bin` (possibly followed by `NoClassDefFoundError: org/eclipse/core/runtime/Plugin`). EDT uses `<java.io.tmpdir>dtgraphics-form.bin` without inserting a separator: change `-Djava.io.tmpdir=C:\edt_tmp` to `-Djava.io.tmpdir=C:\edt_tmp\` and restart EDT. If nothing helps, remove the buffered-render flag and restart EDT.
- On Windows, `nativeFormBufferedLayoutRender.atStartup: off` means EDT was launched without the flag: add `-DnativeFormBufferedLayoutRender=true` after `-vmargs` in `1cedt.ini` and restart EDT. Linux and macOS need no buffered-render flag. `unknown` means the startup mode probe failed.
- `enabledTools` < `totalTools` is normal when progressive disclosure is on - use `list_toolsets` / `enable_toolset` to reveal more.
