# Batch method replacement

The user approved adding `edits[]` after discussing three method replacements without sending the complete module. Extend `write_module_source`, keeping one module hash, one proposal and human-only Apply. Existing single-edit requests stay compatible.

`edits` contains 1–50 objects with exactly string `methodName` and `source` (one complete method). It implies `mode=replaceMethods`; the same mode may be supplied explicitly. Do not mix it with top-level source/methodName/oldSource/expectedSource/overwrite or another mode. Require the existing whole-module expectedHash. Cap combined raw incoming source at the existing 500,000-character limit. Reject malformed/null/non-object entries and duplicate targets case-insensitively; never silently drop an entry.

Each replacement must resolve uniquely against the original module and pass existing method-name/kind/span guards. Prepare all changes in memory and splice original ranges from the end; syntax-check the combined result once. One invalid replacement leaves no review and no source mutation. Preserve BOM, line endings, unchanged text and incoming-character normalization. Reuse the existing late hash/workspace lock and review commit guards. One requestId covers the entire ordered batch; changed entries conflict and replay never duplicates the write.

Alternatives: three ordinary calls save tokens but require three approvals; whole-module replacement keeps one approval but resends unchanged text. An array of named replacements addresses the approved use with the existing editor engine. General mixed inserts/deletes/searchReplace batches, multiple modules, automatic Apply and new transaction frameworks are outside this change.

Builder choices: the explicit mode name, 50-entry cap and replacement-only first version keep the contract small. Java 17 source, existing Gson/Eclipse APIs, no new dependencies. Validate with unit RED/GREEN, real pending-review lifecycle and isolated live MCP; canonical build, generated docs/golden, relevant e2e and conformance. Known host-only baseline e2e failures remain tracked separately in edt-11z/edt-cya.
