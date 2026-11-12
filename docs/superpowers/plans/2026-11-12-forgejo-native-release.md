# Native Forgejo release plan

**Goal:** Собирать и публиковать стабильный p2 непосредственно из приватного зеркала.
**Spec:** [design](../specs/2026-11-12-forgejo-native-release-design.md).
**Architecture:** tag checkout + отдельные CI tools → общая build action → local p2 publisher.

## Global Constraints

Worktrunk branch; интеграция в master; push только origin/master; без PR.
Нет GitHub API/архива в publisher, токен/URL/сайт сохраняются. Существующая 1.0.6
не перезаписывается. GitHub и обычные фильтры CI не меняются.

## Review Focus

Сборка должна использовать HEAD тега, а не master tooling; тег должен принадлежать
master. Публикация возможна только после всех тестов. Неверная версия, изменённый
тег, downgrade и ошибка build не должны менять сайт; секрет доступен только
публикационному шагу. Старые теги не обязаны содержать современные CI actions.

### Task 1: Local p2 publisher

**Files:** `.github/scripts/publish-forgejo-update-site.py`,
`.github/scripts/test_publish_forgejo_update_site.py`.
**Interfaces:** CLI `--tag vX.Y.Z --site PATH`, cwd=checkout тега; local p2 → public repo.

- [x] Baseline и RED: отвергнуть HEAD другого тега, тег вне master и изменение SHA
  той же опубликованной версии; подтвердить происхождение в новом marker.
- [x] Удалить GitHub release/download/ZIP readiness; сохранить валидатор и push.
- [x] GREEN: `.github/scripts` unittest и diff check; commit.

### Task 2: Native release workflow

**Files:** `.forgejo/workflows/deploy-update-site.yml`, `README.md`.
**Interfaces:** Task1 local p2 publisher; current shared build action on tagged source.

- [x] Явный обязательный тег для manual; regex + full tag checkout, master ancestry;
  отдельный checkout CI tooling из master в `.release-tools`.
- [x] JDK/Maven/GTK/guard/cache, Tycho set-version, общая build action, p2 artifact,
  local publisher и отчёты тестов always. Секрет только у publisher.
- [x] Schema/shell validation и обновление README; commit, review, master push+mirror.

### Task 3: Live acceptance

**Files:** `docs/superpowers/reports/2026-11-12-forgejo-native-release.md`.
**Interfaces:** Реальный manual v1.0.6; p2/site URL неизменен.

- [x] Полный собственный build в Forgejo с тестами, p2, JaCoCo и успешным publish no-op.
- [x] Проверить p2/отчёты и неизменность существующего output commit 1.0.6.
- [x] Итоговый report/checklist в master, только master push и sync.
