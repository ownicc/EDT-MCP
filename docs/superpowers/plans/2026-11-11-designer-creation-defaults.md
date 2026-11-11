# Designer creation defaults implementation plan

**Goal:** Match native EDT initialization for the five confirmed beads findings.

**Architecture:** Preserve existing creation and copy semantics. Initialize new extensions before copying compatible old properties; use owner-aware metadata initializers for the confirmed child kinds. Existing objects retain user values.

**Tech stack:** Java, EMF, EDT 2026.2, JUnit 4, Maven/Tycho.

**Spec:** Beads edt-a72, edt-14i, edt-8zq, edt-efa, edt-qih (confirmed audit 2026-11-11).

## Constraints and review focus

- Local working branch; merge into local master after verification. Preserve concurrent upstream integration and unrelated dirty files.
- Platform 8.5 runtime is unavailable; model comparisons with native EDT factories cover 8.3.27 and 8.5.1.
- Preserve explicit properties, native eIsSet copy behavior, IDs, containment, handlers and existing extInfo identity.
- Check type replacement and initial creation; query conversion uses the public supported path.
- Check version boundaries (8.3.20/21 and 8.5.1); do not broaden unsupported metadata inputs.

## Task 1: Metadata children (edt-14i)

Files: CreateMetadataTool.java; dedicated metadata initialization tests.
- [x] Add failing tests for ten tabular-section kinds (producedTypes/objectType/rowType), commands (Auto), URLTemplate (/*), Operation (return QName), and explicit property priority.
- [x] Route these confirmed types through the existing parent-aware IModelObjectFactory creation path.
- [x] Verify regression tests against real EDT initializers and existing CreateMetadataTool tests.

## Task 2: Item extensions (edt-a72)

Files: FormElementWriter.java; FormElementDefaultsTest.java or dedicated real-model tests.
- [x] Add failing model comparisons with native FormObjectFactory for supported field and decoration kinds and retyping, including Calendar, TrackBar, Spreadsheet, PDF, Picture and 8.5 RadioButton.
- [x] Initialize new extensions with native defaults before existing copySameFeatures. Preserve existing holder values.
- [x] Verify the matrix and existing form-default tests.

## Task 3: Dynamic lists and document root (edt-8zq, edt-efa)

Files: FormElementWriter.java; real-model regression tests.
- [x] Reproduce query/mainTable conversion and table defaults; reproduce new DocumentFormExtInfo repostOnWrite=false.
- [x] Initialize only fresh extension holders; pass version where needed. Existing false/custom values survive repeat synchronization.
- [x] Verify four dynamic-list flags, table references and version boundary, document creation and repeat sync.

## Task 4: Versioned item defaults (edt-qih)

Files: FormElementWriter.java; real-model regression tests.
- [x] Add failing tests for command selectedRowsUse, buttonImportance, table rowSelectionMode and autoMaxCardHeight on 8.5.1.
- [x] Thread version into creation helpers and apply native version-specific values without rewriting existing items.
- [x] Verify both 8.3.27 and 8.5.1.

## Task 5: Integration

- [x] Run Maven/Tycho verify for the whole project, inspect actual test reports and diff checks.
- [x] Fresh independent code review; resolve concrete findings and verify affected behavior.
- [x] Commit only task files, merge locally into current master, close the five beads with evidence and limitations.

## Execution evidence

- Worktree `/tmp/edt-mcp-creation-defaults-2026-11-11`, branch `fix/designer-creation-defaults`, base `b325b4c4`.
- Metadata: four failing regressions on the original implementation; four pass after routing to native initializers. The tabular-section test exercises all ten supported owner kinds.
- Forms: a 176-case matrix compares real field/decoration attributes with native EDT factories across 8.3.12, 8.3.16, 8.3.27 and 8.5.1. Additional model tests cover generated document forms, public dynamic-list query conversion, 8.5.1 item defaults, and table rebinds across 8.3.20/21.
- Fresh independent review found PDF status chrome was skipped by the persisted item traversal. Reproduced lookup failure and duplicate creation; corrected traversal through non-item containment holders. Regression checks status/menu/tooltip lookup, duplicate refusal, and unique IDs. Existing transient/derived filtering remains in PersistedContents.
- Final focused form run: 183 tests pass. Diagnostics and red/green logs are under `/tmp/edt-creation-fix` (temporary evidence; the committed regressions preserve the checks).
- Full Maven/Tycho verify restarted after the review fix so compilation and the complete suite use the final source.
- Limitation: no platform 8.5 GUI/client execution. Native BM command group assignment requires a live engine; the corrected path invokes the native owner initializer that implements it.

Final verification: Maven/Tycho `mvn -o verify --batch-mode` BUILD SUCCESS in 10:37; 13848 tests across 752 classes, 0 failures, 0 errors, 25 skipped. `git diff --check` clean. Full suite includes the review regression and native metadata initializer tests.

Integration verification: concurrent master commit `6c0c07ac` (screenshot page restoration) merged without conflicts. Targeted Maven/Tycho verify on the merged source passed 299 tests (FormPageSwitchTest, both new form test classes, FormElementDefaultsTest, CreateMetadataToolTest, CreateMetadataMemberInitializationTest), zero failures/errors/skips. The 13,848-test full run preceded this independent three-file concurrent change; it is not reported as a second full run on the merged commit.
