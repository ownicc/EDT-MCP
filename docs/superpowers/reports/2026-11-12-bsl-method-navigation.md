# BSL method navigation verification

Implemented the approved [mockup](../mockups/2026-11-12-bsl-method-navigation.html)
as a native SWT sidebar. Method identities, line counts and jump targets come
from the existing comparison; the rendering/parser algorithms are unchanged.
The EDT.Comfort structure panel and Git method list were consulted as behavioral
references. No private hooks or dependencies were imported.

## Verified behavior

- Name search and combined type filters retain method identity. A moved-and-edited
  method matches either filter and occurs only once.
- Selection reveals an actual edit, or the declaration for a move with no token
  changes, including moved methods with whitespace-only edits and documentation.
- Caret/change navigation selects the corresponding list entry without taking focus.
  Existing inline text search retains its prior method-selection behavior.
- Enter/double-click opens the existing detached read-only method comparison.
- Added/removed methods, outside code, duplicate identities and the external
  comparison engine retain honest one-sided/outside/fallback entries.
- Collapsing/restoring the sidebar and both viewer themes work in native EDT.

## Evidence

The model tests and opt-in native SWT test passed. The UI test is enabled only
with `EDT_MCP_NAVIGATION_UI_TESTS=true` and was run under Xvfb with
`GDK_BACKEND=x11` and `WAYLAND_DISPLAY` unset.

`experiments/bsl-jgit-viewer/run.py --quality` passed using the freshly built
bundle in a copied EDT installation and workspace. Evidence:
`/tmp/edt-navigation-tools/native-final/viewer-results.json`, with light/dark
captures in the same directory. The 95 frozen inputs passed complete monotonic
source-line coverage and valid-span checks; legacy frozen token differences are
reported separately by the existing probe. This is not a claim that the old
frozen token output is unchanged.

An independent review found two issues. Both were reproduced in actual EDT
before their fixes: external comparisons mislabeled as outside code, and
formatting-only moves jumping to documentation. The final native probe includes
both regressions and passes. Native test processes were stopped afterwards.

The separate agent-pack validator passed (13 skills, 111 tools and 1 proxy tool).
Full Maven/Tycho `mvn -o clean verify --batch-mode -T 1` completed with
**BUILD SUCCESS** (10:33). The native-tested bundle is byte-identical to the
final build artifact. Full log: `/tmp/edt-navigation-tools/final-full.log`.

## Scope

No tool schema, transport, metadata mutation or approval behavior changed.
The local feature was not deployed to the user's regular EDT installation.
Windows rendering and the previously reported scrollbar size remain separate
checks on the user's Windows configuration. No release or push is implied.

Full suite: 13853 tests, 0 failures, 0 errors, 25 skips.
