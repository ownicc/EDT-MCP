# Собственная релизная сборка и публикация Forgejo

Forgejo теперь собирает стабильный тег из приватного зеркала и публикует свой
локальный p2. В publisher удалены GitHub Release API, ожидание релиза, загрузка
готового ZIP и выбор latest через GitHub. GitHub release/Pages продолжают свою
цепочку. URL EDT, публичный выходной репозиторий и ограниченный токен сохранены:

```text
https://forgejo.ownicc.ru/ownicc/EDT-MCP-update-site/raw/branch/master/
```

## Цепочка

1. Стабильный `vX.Y.Z` попадает в зеркало либо явно указывается при manual run.
2. Workflow checkout-ит этот тег в корень workspace и проверяет включение в
   `origin/master`. Для push проверяется также SHA события с поддержкой annotated tags.
3. Современные инструменты CI берутся из отдельного `.release-tools` checkout
   master: старый тег v1.0.6 ещё не содержит общей action. Исходники плагина
   остаются в корне checkout тега.
4. JDK25, Maven3.9.16, GTK3, EDT guard и кеш готовятся как в обычной сборке.
   Tycho set-version устанавливает номер из тега, общая action выполняет
   проверки skills, Python, Maven verify с Java-тестами и JaCoCo report.
5. Готовый p2 сохраняется как artifact, затем локальный publisher повторно
   проверяет HEAD тега, принадлежность master и корректность p2, и публикует
   одним git push. Surefire-отчёты загружаются при `always()`.

Секрет `UPDATE_SITE_TOKEN` передаётся только publisher. Новый `release.json`
содержит версию, SHA исходников и Forgejo tag URL. Сохраняются старые JAR,
запрет downgrade, отказ non-fast-forward push и неизменность уже опубликованных
версий. Для известного source SHA попытка повторного номера с другим SHA запрещена.
Обычные master/docs изменения не публикуют сайт. Внешние зависимости сборки
(actions, JDK, Maven/EDT/Eclipse repositories) остаются прежними; независимость
здесь означает отсутствие зависимости от готового GitHub релизного артефакта.

## Проверка

- Baseline прежнего publisher: 12 Python-тестов прошли.
- RED → GREEN новых проверок: точный HEAD стабильного тега, тег вне master,
  происхождение в marker, конфликт SHA той же версии, сохранение legacy-версии,
  отказ `.git` и symlink в локальном p2. Сохранён тест конфликтующего push.
  Итоговый набор publisher/validator: 12 тестов, без ошибок.
- В локальном checkout v1.0.6 с отдельными текущими tools прошли реальные
  четыре шага общей action: 13 skills, 111 tools + proxy; Python 5+5+315.
  Дополнительно прошли 12 тестов текущего publisher.
- Реальные shell-блоки workflow проверены на стабильном, multiline и prerelease
  входе; guard принимает lightweight и annotated event SHA. Forgejo Runner13.1.0
  подтвердил схему workflow/actions. `git diff --check` прошёл.
- Независимое ревью `review_native_forgejo_release`: actionable замечаний нет.
- Реальный [Forgejo run 7](https://forgejo.ownicc.ru/ownicc/EDT-MCP/actions/runs/7)
  завершился `success`.

| Назначение | Проверенный SHA |
| --- | --- |
| Workflow и CI tools | `ef2d55fefa2850e59638045dd9943f9286f46a2d` |
| Исходники плагина, тег v1.0.6 | `bf39784cb58063ab0560525481a54cb61c13adc9` |

Раздельные HEAD и `Bundle-Version: 1.0.6` подтверждены read-only probe внутри
действующего job. Скачаны собственные artifact `MCP-EDT.v1.0.6` и Surefire reports.
Архивы прошли CRC, p2 metadata/bundle/feature JAR проверены существующим
`validate-update-site.py`. В 752 Java-наборах 13 848 тестов: **13 808 прошли,
40 пропущены, failures=0, errors=0**.

## Существующая версия и границы проверки

На сайте уже находилась 1.0.6 из прежней цепочки. Её повторная сборка не заменяет
бинарные файлы тем же номером версии: publisher успешно проверил собственный
локальный p2 и завершился без нового коммита. Выходной SHA до и после run7:
`28b13050b5ce5a703aa25ab67cf11d755a819990`. Metadata остаются анонимно доступны
с HTTP200. Следующая новая версия будет опубликована из собственной сборки
Forgejo с source SHA в marker. Новый релиз или тестовый тег ради проверки
не создавался; tag-trigger проверяется на следующем настоящем релизе.

Данные проверки сохранены в `/tmp/edt-forgejo-tools-20261112/`:
`native-release-unit.log`, `native-tag-layout.log`,
`artifacts/native-forgejo-p2.zip`, `artifacts/native-forgejo-tests.zip`,
`verify-native-release.py`.

Изменения разработаны в Worktrunk ветке `fix/forgejo-native-release`, включены
в master; опубликована только master, PR не создавался. Параллельный коммит
610b02ce сохранён; его изменения не относятся к приёмке старого тега v1.0.6.
Для интеграции использован skip-ci: полный приёмочный прогон выполнен отдельным
релизным workflow, без дублирующей сборки master ради Python/config изменения.
