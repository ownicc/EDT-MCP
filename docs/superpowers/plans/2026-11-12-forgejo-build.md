# Dual GitHub and Forgejo Builds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native execution, or superpowers:subagent-driven-development if the user selects that method. Track execution with the checkboxes below.

**Goal:** Независимо собирать плагин с тестами в GitHub Actions и зеркале Forgejo, переиспользуя общие шаги.

**Architecture:** Два workflow вызывают локальную composite action. Подготовка JDK, кеш и публикация артефактов остаются в workflow; существующие setup actions, EDT guard и Maven retry переиспользуются.

**Tech Stack:** Bash, Python 3, Maven 3.9.16, JDK 25, Tycho 5.0.4, GitHub Actions, Forgejo 15.0.11.

**Spec:** [2026-11-12-forgejo-build-design.md](../specs/2026-11-12-forgejo-build-design.md)

## Global Constraints

- Работать в существующем Worktrunk `design/forgejo-build`; не менять незакоммиченные файлы исходного checkout.
- Сохранять JDK 25, Maven 3.9.16, Tycho 5.0.4 и `clean verify --batch-mode --no-transfer-progress -T 1C` с Java-тестами.
- Не переносить релизы, Pages, SonarCloud, E2E/conformance workflows и proxy в Forgejo; сохранять их работу на GitHub.
- Не публиковать рабочую ветку; после проверок интегрировать локально в `master` и отправлять только `origin/master`, без PR.
- Не помещать пользовательский PAT в workflow, репозиторий или вывод команд.
- Не добавлять библиотек проекта и тестов, повторяющих содержимое YAML; проверять существующие проверки и реальные результаты сборки.

## Review Focus

- После извлечения общих шагов GitHub должен продолжить запускать все нынешние Python- и Java-тесты, JaCoCo, SonarCloud и публикацию результатов.
- Ошибка проверок или Maven должна завершать job ошибкой, а не публиковать успешный пустой артефакт.
- Forgejo должен checkout-ить собственный приватный репозиторий выдаваемым CI токеном и собирать SHA события, а не другой коммит GitHub.
- Обновление зеркального `master` должно запустить Forgejo workflow; ручной запуск не заменяет эту проверку.
- Runner label, JS actions, кеш и загрузка p2 должны работать на реальном runner; ответы API 403/404 не считать подтверждением его отсутствия.

### Task 1: Общая сборка и сохранение GitHub workflow

**Files:** Create `.github/actions/build-plugin/action.yml`; modify `.github/workflows/build.yml`.

**Interfaces:** Action потребляет checkout репозитория, JDK/Maven на PATH и стандартные `GITHUB_WORKSPACE`/`GITHUB_OUTPUT`; возвращает `outputs.version`. Выходной p2 остаётся в `mcp/repositories/com.ditrix.edt.mcp.server.repository/target/repository/`.

- [x] Запустить текущие Python-проверки как baseline: `python3 docs/validate_agent_skills.py`, `python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v`, аналогичные discover для `docs/tests` и `tests/e2e/runner_tests`. Зафиксировать реальные результаты.
- [x] Вынести эти четыре проверки, текущий вызов `mvn-retry.sh`, JaCoCo report и извлечение версии в composite action с явным `shell: bash`. Сохранить `MAVEN_OPTS=-Xmx2g -XX:+UseParallelGC` для основной сборки и корректный рабочий каталог каждого шага.
- [x] Заменить соответствующие шаги GitHub одним вызовом `uses: ./.github/actions/build-plugin` с `id: build`. Переключить имя артефакта на `steps.build.outputs.version`; оставить существующие события, setup, EDT guard, кеш, SonarCloud и публикацию результатов. Отсутствие p2-каталога при успешном build должно ломать upload через `if-no-files-found: error`.
- [x] Проверить GitHub workflow линтером, YAML/composite interface и shell-синтаксис. Выполнить реальные run-шаги composite action в подготовленном локальном окружении; ожидать прохождение всех Python/Java-тестов, отчёт JaCoCo, непустую версию и p2-результат. Использовать найденный JDK `/opt/1C/1CE/components/axiom-jdk-full-25.0.4+5-x86_64` и Maven из существующего setup action. Временные инструменты проверки держать вне репозитория.
- [x] Просмотреть diff на сохранение GitHub-проверок и распространение ошибок, выполнить `git diff --check`, закоммитить общую action и обновление GitHub workflow.

### Task 2: Forgejo workflow и инструкции запуска

**Files:** Create `.forgejo/workflows/build.yml`; modify `README.md`, раздел `Building from source`.

**Interfaces:** Workflow вызывает action Task 1 и её `outputs.version`; runner label берётся из проверенных настроек пользовательского runner. События: `push.branches: [master]`, `workflow_dispatch`.

- [x] Уточнить существующий runner, его label, версию и образ; если runner отсутствует, согласовать место размещения, не устанавливать постоянный runner на случайную машину. Сейчас repository runners API возвращает 404, user runners API — 403 с требованием `read:user`; сообщённые пользователем настройки могут заменить расширение токена.
- [x] Создать Forgejo workflow с checkout собственного репозитория и SHA события, JDK 25, существующими `setup-maven` и `edt-channel-guard`, кешем Tycho и общей action Task 1. Внешние actions указывать явно: `https://data.forgejo.org/actions/checkout@v7`, `https://data.forgejo.org/actions/setup-java@v6`, совместимый cache action. Проверить наличие cache-тега и совместимость JS runtime с runner перед выбором версии.
- [x] Для p2 и диагностических Surefire-отчётов использовать `https://data.forgejo.org/actions/upload-artifact@v3-node20` (существование тега проверено). Отчёты загружать при `always()`, основной артефакт — только после успешной сборки, с `if-no-files-found: error`. Не вызывать GitHub Checks/SonarCloud из Forgejo.
- [x] Добавить в README краткие инструкции: два workflow и общая action, требуемый runner label/образ и сеть, включение Actions после появления `.forgejo/workflows`, штатный CI токен, где скачивать p2-артефакт. Не менять настройки зеркала.
- [x] Проверить YAML и shell-блоки Forgejo, существование локальных action paths и outputs, отсутствие случайных вызовов GitHub API и персонального токена. Проверить собранный p2: metadata, bundle/feature JAR и целостность архива. Не требовать совпадения timestamp/qualifier между независимыми сборками.
- [x] Выполнить `git diff --check` и закоммитить Forgejo workflow с документацией.

### Task 3: Проверка двух CI и интеграция

**Files:** Create `docs/superpowers/reports/2026-11-12-forgejo-build.md`; update plan checkboxes and design status.

**Interfaces:** Один интегрированный SHA `master`, доступные GitHub и Forgejo workflows Tasks 1–2, доступный Forgejo runner.

- [x] Просмотреть полный diff на соблюдение дизайна; исправить только конкретные найденные проблемы. Не расширять тестовые прогоны после успешных проверок без нового изменения или риска.
- [x] После локальных проверок интегрировать ветку в локальный `master`, сохранив исходный dirty checkout; отправить только `git push origin master`. Учитывать, что для первого реального запуска новые workflows должны оказаться в `master`.
- [x] Убедиться, что зеркало получило тот же SHA и `.forgejo/workflows/build.yml`; проверить включение Actions на сервере и в репозитории, подключение runner и доступность кеша из job. Не включать неподготовленные `.github/workflows` в зеркале.
- [x] Дождаться успешных GitHub Build и Forgejo Build одного SHA; скачать p2 с обеих платформ, проверить метаданные и JAR. Проверить и ручной запуск Forgejo, и запуск от синхронизации зеркала; сохранить ссылки и SHA.
- [x] Записать фактические результаты и ограничения в report. Если инфраструктура не позволяет выполнить запуск, прямо перечислить оставшееся и не объявлять поддержку Forgejo проверенной. Итоговые изменения документации также включить в `master`; рабочую ветку не публиковать.
