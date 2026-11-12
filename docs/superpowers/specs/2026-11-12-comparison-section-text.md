# Exact BSL section text diff

User approved the behavior explained in the conversation for Beads edt-mmh.2.

`get_comparison_node(textDiff=...)` must compare the addressed section rather
than silently widening it to the owning module. A module request keeps its
current whole-module behavior. Read both versions from the active comparison
session inside its existing read boundary; format immutable snapshots outside it.

Use public EDT comparison regions. Region/preprocessor/main sections include
only their own fragments, excluding child methods and nested regions. A method
includes its signature/body children when EDT represents them separately; retain
comments supplied by those regions. Preserve EDT structural/merge status.

Render each paired fragment separately with its original UTF-16 offset/length
and 1-based source line/column on each side. Unified hunk line numbers refer to
the original module. Never include text between disjoint fragments. Mark partial
source lines explicitly; snippets are observations, not an applicable patch.
Absent sections, empty existing sections and read/range failures remain distinct.
Missing/invalid ranges yield unavailable, never equality or inferred deletion.

Keep current input limits (500000 UTF-16 units / 20000 lines per side) and shared
output limits (maxDiffLines, at most 65536 formatted bytes), across all fragments.
No new parameter, dependency, parser, UI control, or write behavior. Existing edit
preview formatting remains unchanged. Update tool schema/guide to describe scope.
