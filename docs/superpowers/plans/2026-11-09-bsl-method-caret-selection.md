# Selecting a moved method from its body

User approved caret/click selection with the chosen name beside Compare method.
Alternative: a separate moved-method list; that remains planned in `edt-mmh.9`.
This step implements `edt-mmh.7` and retains the attended three-method Kasl demo.

**Goal:** choose the exact moved pair by clicking or moving the caret within
either endpoint; show its name; disable comparison outside moved methods.
**Architecture:** reuse overview move ranges and aligned rows. Selection must
not jump the viewport. Explicit navigation and programmatic search updates
suppress caret-driven retargeting, preserving existing search behaviour.
**Tech stack:** existing Java/SWT/JFace/native probe. No dependencies or workspace.

- [ ] Native RED: caret outside method clears target; caret in method enables
  comparison without badge; click with unchanged caret also selects it.
- [ ] Add overview selection listeners, guarded navigation/search selection,
  toolbar name/placeholder and localized guidance. Hide them in method-only tabs.
- [ ] Focused verify and native95 with caret/click, unchanged viewport,
  search preservation and paired navigation; fresh bounded review.
- [ ] Merge locally, install verified artifact, show three method comparisons
  in existing Kasl with Adwaita light/NVIDIA EGL and no source write.
