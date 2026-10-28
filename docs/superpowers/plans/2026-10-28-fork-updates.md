# Fork Updates Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans; implement in the existing isolated feature worktree.

**Goal:** Обновлять установленный форк из ownicc/EDT-MCP.
**Architecture:** Существующий GitHub checker и p2; общий deploy workflow вызывается из release workflow.
**Tech Stack:** Java, Tycho, GitHub Actions, Eclipse p2.
**Spec:** docs/superpowers/specs/2026-10-28-fork-updates-design.md

## Global Constraints

- Адрес релизов: ownicc/EDT-MCP; p2: https://ownicc.github.io/EDT-MCP/.
- Версии: vX.Y.Z; первая версия форка v1.0.1.
- Сохранять лицензию, авторство и Git upstream.
- Не закрывать работающий EDT.

## Review Focus

- Создание релиза через GITHUB_TOKEN должно приводить к публикации p2.
- Ручной запуск должен использовать точный тег, а не случайный последний prerelease.
- Неполный архив не должен заменять работающий update site.
- Bundle, feature и release должны иметь одну версию.
- Ранее зарегистрированный оригинальный p2-источник требует отдельного отключения.

### Task 1: Переключить и проверить полный путь обновлений

- [x] Заменить два URL в UpdateChecker.java и update/discovery в feature.xml.
- [x] Сделать deploy-update-site.yml вызываемым, передавать точный тег из release.yml;
      проверять стабильный релиз и структуру архива, выводить фактический URL Pages.
- [x] Обновить инструкции установки и описать версии и перенос upstream.
- [x] Проверить Actions через actionlint, shell-блоки и ошибки публикации локально;
      собрать плагин каноническим source/compile.sh и проверить p2-метаданные.
- [x] Независимо проверить diff, исправить существенные замечания.
- [x] Подготовить GitHub Pages и переключение текущего EDT; публиковать только
      проверенный конкретный результат в пределах авторизации пользователя.

Итог: опубликован v1.0.3, полный CI и проверка настоящим p2 director прошли.
Для применения в работающем EDT подготовлена отдельная команда установки;
он остаётся на 1.0.0 до закрытия и выполнения этой команды пользователем.
