# Forgejo p2 publication implementation plan

**Goal:** Дать EDT постоянный публичный URL стабильных обновлений в Forgejo.
**Architecture:** Лёгкий workflow загружает готовый GitHub release ZIP и публикует
проверенный p2 в отдельный публичный репозиторий, без Maven и нового сервера.
**Spec:** [design](../specs/2026-11-12-forgejo-update-site-design.md).

## Global Constraints

Локальная ветка Worktrunk; интеграция в master и push только origin/master.
PAT не передаётся CI. Стабильные релизы только из master. GitHub Pages сохраняется.

## Review Focus

Некорректный тег/архив, prerelease/draft, downgrade и ошибка валидации не должны
менять опубликованный сайт. Git push атомарен; старые JAR доступны. Токен имеет
доступ только к p2-репозиторию. Обычные сборки и документация не публикуют сайт.

### Task 1: Публикация и регрессионные проверки

**Files:** `.github/scripts/publish-forgejo-update-site.py`,
`.github/scripts/test_publish_forgejo_update_site.py`.
**Interfaces:** CLI `--tag vX.Y.Z` (optional), env `UPDATE_SITE_TOKEN`; готовый
GitHub stable release ZIP → `EDT-MCP-update-site/master`.

- [x] Сначала тесты: стабильный тег, prerelease/draft, безопасная распаковка,
  неправильная версия/повреждённый p2, сохранение старых JAR, запрет downgrade,
  повторная публикация без изменений, отказ push при конкурентном обновлении.
- [x] Реализовать stdlib + git, переиспользовать `validate-update-site.py`.
- [x] Запустить `.github/scripts` unittest и `git diff --check`; commit.

### Task 2: Workflow, доступ и инструкции

**Files:** `.forgejo/workflows/deploy-update-site.yml`, `README.md`.
**Interfaces:** Скрипт Task 1, secret `UPDATE_SITE_TOKEN`, runner `edt-build`.

- [x] Workflow: push tags `v*`, manual optional tag, node24 + git/python,
  checkout master с полной историей, проверка tag ancestor master, вызов скрипта.
- [x] Создать публичный p2 repo и repository-scoped token; сохранить secret.
- [x] Проверить схему runner и shell; описать URL и порядок обновления EDT.
- [x] Commit, интеграция в master, push только master, синхронизация зеркала.

### Task 3: Живая проверка

**Files:** `docs/superpowers/reports/2026-11-12-forgejo-update-site.md`.
**Interfaces:** Ручной запуск Task 2 с v1.0.6; публичный raw URL.

- [x] Запустить реальный Forgejo workflow; проверить успешную публикацию.
- [x] Анонимно скачать metadata/JAR, проверить валидатором и p2 director.
- [x] Зафиксировать фактический результат, закрыть checklist и включить в master.
