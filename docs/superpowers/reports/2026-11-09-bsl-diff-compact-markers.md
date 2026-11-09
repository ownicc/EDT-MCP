# Compact BSL diff markers — 2026-11-09

Beads `edt-mmh.10`. User requested half the gap between +/− and source numbers,
a clearer alternative to M1, and less space between that control and numbers.

- Sign-to-number gap is one measured space instead of two. The font-aware base
  gutter shrinks accordingly, with a smaller minimum for short line numbers.
- One compact `↔` control replaces the visible M1/M2 caption at each declaration.
  Its tooltip names the moved method and the destination version/source line.
  Internal pair identities, source order, paired jump and method comparison stay
  intact. The move column uses the icon's measured width and reduced padding.
- Existing focused verify: **31 tests, zero failures/errors**.
- Existing real EDT/SWT quality probe: **95 cases**, both palettes, move badge
  placement/click, paired jump, method-only tab and inline search all PASS.
  `/tmp/edt-diff-quality/compact-focused.log` and `compact-native-final.json`.
- Actual light/dark captures were inspected; `git diff --check` passes. No new
  test suite or dependency was introduced for this visual adjustment.

The verified build is installed in the existing Kasl workspace/EDT. Launches
retain `GTK_THEME=Adwaita:light` and the user's NVIDIA EGL vendor JSON. Sources
are shown only as a pending review; acceptance is left to the user.

Installed version: `1.0.0.202611092013`, SHA-256
`6a72990f0e670f4999b71df98a6075f5c36d45e91a39843a90905fc07a8b1012`.
Actual Kasl captures: `/tmp/edt-diff-quality/Kasl-compact-move.png` and
`Kasl-compact-addition.png`. Both were inspected. All 165 original source
snapshot files are unchanged; review `695b865a-3009-4a85-a776-6b3b248235d2`
is awaitingApproval, applied=false, mutationCommitted=false.
