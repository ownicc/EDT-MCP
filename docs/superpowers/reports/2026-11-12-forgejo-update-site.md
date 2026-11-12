# Стабильный update site в Forgejo

Это отчёт о первоначальной публикации готового GitHub архива. Текущая цепочка
заменена [собственной релизной сборкой Forgejo](2026-11-12-forgejo-native-release.md).

Настроен постоянный публичный p2 URL:

```text
https://forgejo.ownicc.ru/ownicc/EDT-MCP-update-site/raw/branch/master/
```

В EDT: **Help → Install New Software → Add… → Location**, затем оставить сайт
включённым в **Available Software Sites** и использовать **Check for Updates**.
Чтобы загрузки шли только с Forgejo, прежний сайт GitHub можно отключить там же.
Встроенное уведомление плагина о релизах продолжает обращаться к GitHub.

## Публикация

`.forgejo/workflows/deploy-update-site.yml` запускается при получении зеркалом
тега `vX.Y.Z` и вручную. Скрипт берётся из master, проверяет принадлежность тега
истории master, ждёт готового стабильного GitHub Release до 30 минут, загружает
его ZIP и проверяет существующим валидатором p2. Maven повторно не запускается.
Сайт содержит тот же релизный архив, что и GitHub Pages. Токен не включается в URL.

После проверки файлы публикуются одним git push в отдельный публичный
`ownicc/EDT-MCP-update-site`. Старые JAR сохраняются, предыдущие metadata
заменяются; downgrade запрещён. Повтор той же версии не создаёт новый коммит.
Ошибка до push и отклонённый push сохраняют прежний публичный сайт.
Обычные коммиты master и документация не запускают публикацию.

Сервисная учётная запись `edt-mcp-publisher` ограничена и имеет write collaborator
доступ только к выходному репозиторию. Её отдельный токен имеет scope
`write:repository`, `resource_all_repos=0` и привязку только к этому репозиторию
(проверено в конфигурации Forgejo). Он хранится в Actions secret
`UPDATE_SITE_TOKEN` приватного исходного зеркала. Пользовательский PAT туда не
передавался. Временный административный bootstrap token удалён после настройки;
отсутствие проверено. Новые серверы и изменения reverse proxy не потребовались.

## Проверка

- 12 Python-тестов `.github/scripts` прошли, включая запрет небезопасных путей,
  symlink, downgrade, невалидного p2 и конфликтующего git push, сохранение старых
  JAR и ожидание завершения загрузки asset. Новые проверки сначала воспроизвели
  отсутствие реализации и преждевременный выход из ожидания.
- Forgejo Runner 13.1.0 подтвердил схему нового workflow и локальных actions;
  `git diff --check` прошёл.
- Независимое ревью: одна Minor о состоянии asset `starter`; исправлена проверкой
  `uploaded`, сценарий `404 → нет asset → starter → uploaded` прошёл. Других
  actionable замечаний не найдено.
- Реальный ручной [Forgejo run 6](https://forgejo.ownicc.ru/ownicc/EDT-MCP/actions/runs/6)
  завершился `success` на SHA `6dce12c0fb345ed74f543e3a6fdafc64d890d7f6`,
  опубликован стабильный релиз `v1.0.6`.
- Выходной коммит сайта: `28b13050b5ce5a703aa25ab67cf11d755a819990`.
- Анонимно скачаны `p2.index`, `content.jar`, `artifacts.jar`, `release.json`
  и оба bundle/feature JAR. Проверены HTTP 200, версия `1.0.6`, metadata и CRC
  существующим `validate-update-site.py`.
- Eclipse p2 director из установленного EDT Start с JDK 25 прочитал новый URL
  и вернул `com.ditrix.edt.mcp.server=1.0.6` и
  `com.ditrix.edt.mcp.server.feature.feature.group=1.0.6`; exit code 0.
  Использовались временные configuration/workspace, установка EDT не менялась.

Проверка выполнена реальным ручным запуском существующего релиза. Следующая
автоматическая публикация произойдёт при поступлении нового стабильного тега;
новый релиз ради проверки не создавался. GitHub Pages остаётся доступен.
Если релизный архив появляется позже 30 минут, публикацию надо повторить вручную.

Локальные подтверждения находятся в `/tmp/edt-forgejo-tools-20261112/`:
`p2-reviewed-unit.log`, `p2-director.log`, `artifacts/forgejo-stable-p2/`.
Рабочая ветка создана через Worktrunk; исходные изменения включены в master,
опубликована только master, PR не создавался.
