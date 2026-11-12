# Changelog

Release history for the [ownicc fork](https://github.com/ownicc/EDT-MCP).
Earlier versions are documented in [GitHub Releases](https://github.com/ownicc/EDT-MCP/releases).
Update this file before tagging each release.

## [1.0.7] - 2026-11-13

### Added

- `problem_snapshots` captures complete EDT diagnostics and compares two snapshots for new, fixed and remaining signature counts. Matching preserves duplicate counts and ignores line shifts; snapshots are bounded and process-local.
- Changed-method navigation in the BSL comparison viewer, with method-name search, type filters and bulk filter actions.
- Exact BSL module-section fragments in configuration-comparison text diffs.

### Changed

- Build Forgejo release tags independently and publish the validated local p2 output to the stable update site.
- Share plugin build checks between GitHub and Forgejo, and filter automatic CI runs by their relevant inputs.

### Fixed

- Clarify BSL method status and move icons, use the approved 16px assets, and require a valid comparison pair before opening a separate method comparison.
- Correct quoted method names in Russian move tooltips.

### Verification scope

The diagnostic-snapshot change passed the full Java suite (13,888 tests, zero
failures/errors, 30 skips), five live EDT scenarios, the tools-list golden check,
319 e2e-harness unit tests and MCP conformance against the existing baseline.
Live checks used EDT 2026.2.2.21 on Linux. The complete 1,898-scenario e2e sweep
was not completed; targeted live verification plus the full unit suite was
explicitly selected for this change.

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

[1.0.7]: https://github.com/ownicc/EDT-MCP/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/ownicc/EDT-MCP/compare/v1.0.5...v1.0.6
