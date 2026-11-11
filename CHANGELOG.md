# Changelog

Release history for the [ownicc fork](https://github.com/ownicc/EDT-MCP).
Earlier versions are documented in [GitHub Releases](https://github.com/ownicc/EDT-MCP/releases).
Update this file before tagging each release.

## [1.0.6] - 2026-11-11

### Added

- Batch `read_method_source` requests through `methodNames`, retaining the fork's per-method `methodHash` safeguards.
- Upstream DCS template reading, group bindings, standard periods, nested formatting, insertion positions and template creation.
- Palette color metadata, choice parameters and choice links for attributes and form fields.
- Application standalone-server stop/start control with an explicit hold until the next start.

### Changed

- Integrate 14 upstream commits through `45593059` while retaining the fork's BSL review and safe-write workflows.
- Add problem-summary grouping, module-path filtering and line-oriented code/DCS search output.
- Address nested subsystems in metadata properties and rights.
- Show exchange-plan content as object and automatic-registration columns in metadata details.
- Align input schemas and tool-preset migrations with upstream compatibility rules while preserving saved fork preferences.
- Derive module and method hashes from the same saved source snapshot in single and batch method reads.

### Fixed

- Restore default form pages after a `showElement` screenshot when no element was previously selected.
- Initialize new metadata members and form extensions with native EDT defaults; preserve explicit values and compatible existing settings.
- Discard stale debugger stop snapshots after stepping from the EDT window.
- Apply upstream Windows IPC/stdio fixes for paths containing spaces and startup without hidden PowerShell.

### Verification scope

Release acceptance uses EDT 2026.2.2.21 on Linux with platform 8.3 available.
Native comparison/project-reset scenarios and checks requiring platform 8.5.1 or an absent runtime are deferred.
These exclusions do not establish verification of every platform/runtime combination.

[1.0.6]: https://github.com/ownicc/EDT-MCP/compare/v1.0.5...v1.0.6
