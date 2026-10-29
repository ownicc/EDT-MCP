# Native structural BSL review prototype

Goal: replace the plain-text review surface with native syntax-highlighted,
read-only columns using difftastic BSL ranges and alignment; verify on the
isolated Kasl project. Continue the existing reviewed-write lifecycle.

The user asked to proceed with the prototype and selected a Kasl copy for
validation. Implementation choice: custom SWT controls inside the existing
CompareEditorInput. This extends the previously selected native compare tab;
Apply/Reject, exact prepared bytes and stale/dirty guards stay owned by the
existing review implementation. The controls are detached snapshots.

For this first Linux prototype the pinned BSL-enabled difft executable is
provided via JVM property `edt.mcp.difftastic`. The executable is not downloaded
at runtime. An absent or failing engine uses the standard compare surface,
now typed as BSL. Packaging other operating systems is a separate follow-up.

1. Verify existing review tests in an isolated feature worktree. Add a failing
   check that compare elements request the installed BSL viewer. Implement the
   extension type without changing stream contents or write lifecycle.
2. Add fixture-driven tests for difftastic JSON: UTF-8 to UTF-16 columns,
   unordered change chunks, complete alignment, formatting-only/new/deleted
   modules and malformed/out-of-bounds data. Add bounded worker-process
   execution, cancellation, private temporary files and cleanup.
3. Render two native read-only StyledText snapshots, line numbers, BSL lexical
   attributes from the EDT injector, token backgrounds, synchronized scrolling,
   and previous/next change buttons. Compute diff off the UI thread; retain
   normal BSL comparison on failure. No copying, patch application or editing
   action is attached to these text controls.
4. Run relevant review/parser tests and the canonical build. Stage the built
   plugin in a separate EDT installation/configuration used only by the test
   launcher. Preserve GTK_THEME=Adwaita:light. Do not alter installed EDT.
5. Show the prototype on a real copied Kasl module. Record actual checks and
   gaps; preserve pending-review exact-byte acceptance behavior. Review the
   patch, commit and merge verified work locally to master. No push or PR.

Review focus: surrogate pairs and Cyrillic byte columns; missing/truncated
engine output; cancellation on tab close; stale asynchronous UI callbacks;
new-file comparison; no structural changes despite real formatting changes;
retaining Apply/Reject as the only write actions.
