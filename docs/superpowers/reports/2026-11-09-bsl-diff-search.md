# BSL diff feedback — 2026-11-09

Beads `edt-mmh.6` (gutter), `.8` (search); `.7` (explicit method selection) and
`.9` (changed-method navigation) remain planned. User selected one search bar,
defaulting to both snapshots. Spec and plan are in the adjacent directories.

## Changes

- Measure line-number/sign space using the actual source font. Preserve that
  width when the overview adds move badges, instead of resetting to 64 pixels.
  Signs have their own column separated from the longest source number.
- Search original snapshots with JFace's literal document search adapter; map
  results to aligned display rows. Padding is never searched as source.
- Shared Find/Ctrl+F bar: Old/New/Both, case sensitivity, side/counter,
  previous/next, Enter/F3, Shift+Enter/Shift+F3, Esc. Both is the default.
- Search preserves the selected move and source bytes; method-only tabs reuse
  the same controls. Compare method currently uses the selected M1/M2 pair.
- Viewer experiment launches default to the user's Adwaita light and NVIDIA EGL
  environment, while allowing explicit overrides for theme regression checks.

## Evidence

- Model tests started RED (missing search API), then passed; focused verify:
  **31 tests, zero failures/errors**, `search-review-fixed.log` under
  `/tmp/edt-diff-quality`.
- Real SWT large-font probe: **24 pt, five-digit source numbers**, PASS.
  `gutter-native-pass.json` and `native-red/gutter-font24-five-digits.png`.
- Independent whole-branch review found F3 could advance twice after a hidden
  refresh following background alignment. Regraded as a navigation defect;
  native reproduction is `search-review-red.json`, followed by the fix.
- Final native EDT/SWT probe: **95 cases**, both palettes, original move badge,
  paired jump, detached read-only method tab, search scopes/case, exact selection,
  keyboard and hidden-refresh regression all PASS (`search-native-final.json`).
- The combined offscreen test initially read a cached pre-scroll badge hit after
  search resizing. The probe isolates the original badge-paint test from the
  search scenario; the original geometry assertions remain unchanged.

Ruling: retain the original 524288 UTF-16 / 20000-line source limits and perform
literal search through the existing JFace adapter. No regex/whole-word/history
features are added; none were requested. Matches on both sides count separately,
in aligned-row, side, then column order; each result identifies its side.

Changes develop in the existing worktree/local branch. No new workspace,
persistent launcher, dependency, push or PR is introduced.
