# BSL method navigation

Approved reference: [interactive mockup](../mockups/2026-11-12-bsl-method-navigation.html).

Add a collapsible SWT sidebar to the full BSL comparison, with a name search,
combined changed/moved/added/removed filters, visible/total count, and one item
per method identity. Moved-and-edited methods match both changed and moved.
Selection reveals the first actual edit (or the moved declaration), synchronizes
with caret/change navigation, and preserves original source ordering. Enter or
double-click opens the existing detached method comparison, including one-sided
added/removed methods. Outside-method changes and ambiguous/parser fallback have
explicit entries; neither invents a method pair. Empty results explain themselves.

Use the existing parser identities, diff spans, move pairs and method comparison.
The sidebar derives counts and jump locations from those same spans; it does not
recompute highlighting. Keep existing change navigation, read-only review and
theme support. Hide the sidebar in a detached method comparison.

Approach: a small derived navigation model plus a native SWT table beside the
existing comparison. An independent parser or a browser embedded in SWT would
duplicate behavior and complicate selection. Native SWT is the implementation
choice; the user approved the interaction and visual mockup.

Verify model behavior with real BSL comparisons and SWT integration under Xvfb.
Do not launch UI test windows on the user's desktop. Windows appearance and
platform-specific scrolling remain a later check on the user's configuration.

Attended visual refinement: use multi-select native toggle buttons for the type
filters. Move arrows use shared vector geometry, at least 16 SWT coordinates and
scaled to row height, with reserved space in the list and gutter. Preserve native
selection/button colors and keep counts inside the table when icons grow.
