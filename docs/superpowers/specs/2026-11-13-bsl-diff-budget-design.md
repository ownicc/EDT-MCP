# Configurable BSL diff budget

The user approved increasing the method-aware BSL diff budget from two to ten
seconds, making it configurable, and explaining fallback with elapsed time.
Keep the 524288 UTF-16-unit and 20000-line limits per side unchanged. Larger
limits require separate measurements because viewer construction includes work
on the UI thread.

## Approach

Reuse the existing MCP Server General preferences. Add a seconds spinner beside
the new-viewer checkbox, default 10, range 1–60. The range is an implementation
choice that allows slower comparisons while retaining a finite budget. Apply/OK
affects subsequently created comparisons; Restore Defaults restores 10.
Snapshot the setting before scheduling the background comparison. Preserve job
cancellation and parser-boundary checks; this is not a hard parser deadline.

A fixed larger constant would not let users tune slower machines. Removing the
budget would let expensive comparisons occupy the serialized worker indefinitely.
No new dependency or global EDT/Comfort hook is needed.

Fallback retains its existing standard viewer and reason, adding localized
elapsed time measured from the structural viewer attempt. Keep errors and size
limits visible. Acceptance, rejection, detached inputs and Difftastic routing
retain their existing behavior.
