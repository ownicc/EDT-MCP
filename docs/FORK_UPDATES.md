# Обновления форка

Наш репозиторий: https://github.com/ownicc/EDT-MCP.
Источник обновлений EDT: https://ownicc.github.io/EDT-MCP/.
Оригинал: https://github.com/DitriXNew/EDT-MCP (Git remote `upstream`).

## Переключение установленного EDT

1. Открыть **Окно → Настройки → Установка/Обновление → Доступные сайты ПО**
   (**Preferences → Install/Update → Available Software Sites**).
2. Отключить `https://ditrixnew.github.io/EDT-MCP/`.
3. Добавить `https://ownicc.github.io/EDT-MCP/` и включить его.
4. Проверить установленную версию в **Справка → О программе → Сведения об установке**.
   При версии ниже нашей проверить обновления через **Справка → Проверить обновления**.
   Если версия оригинала выше нашей, EDT не предложит понижение: один раз удалить
   **EDT MCP Server Feature** через сведения об установке, перезапустить EDT,
   установить форк из нашего источника и снова перезапустить.

Пока первый релиз и сайт не опубликованы, установить проверенный ZIP через
**Справка → Установить новое ПО → Добавить → Архив**. Проверка релизов форка
до первого релиза не показывает обновлений; возврата к оригиналу нет.
При необходимости её можно отключить в **MCP Server → General**.

Адреса в новой сборке не отключают источник, ранее добавленный в EDT.
Обе сборки используют одинаковые ID: включённый источник оригинала может
предложить замену форка с потерей наших доработок.

## Версии и выпуск

Версии форка независимы от оригинала: `v1.0.1`, `v1.0.2`, далее по возрастанию
трёх чисел X.Y.Z. Не добавлять суффиксы: текущая проверка читает три числа.
При переносе новой версии оригинала продолжать нашу последовательность.

1. Проверить и закоммитить изменения, отправить выбранный коммит в наш форк.
2. Один раз включить **Settings → Pages → Source: GitHub Actions**.
   Если окружение `github-pages` ограничивает ветки, в его правилах разрешить
   также теги `v*`, иначе публикация из release workflow будет заблокирована.
3. Создать и отправить тег, например `v1.0.1`, на проверенный коммит.
4. Workflow **Release** устанавливает версию тега через Tycho в bundle/feature,
   выполняет тесты и выпускает `MCP-EDT.v1.0.1.zip` и proxy JAR.
5. После успеха он напрямую вызывает **Deploy Update Site** с тем же тегом.
   Сайт публикуется только после проверки стабильности релиза, совпадения версий
   в p2-метаданных и наличия исправных JAR плагина и feature.

Для повторной публикации запустить **Deploy Update Site** вручную с точным
тегом. Пустое поле выбирает последний стабильный релиз через GitHub API.
Черновики и предварительные релизы не публикуются. Перед повторной публикацией
старого тега убедиться, что не заменяете сайт более старой версией.

`source/compile.sh --version` меняет только имя локального ZIP. Настоящую
версию установки задаёт Tycho в release workflow, а не имя архива.

## Перенос изменений оригинала

Работать в чистом рабочем дереве и отдельной ветке от актуальной ветки форка:

```bash
git fetch upstream
git switch -c integrate/upstream-YYYY-MM-DD
git merge upstream/master
```

Разрешить конфликты, проверить наши настройки обновлений и BSL diff,
собрать и протестировать результат. Затем интегрировать ветку в форк и выпустить
новую нашу версию. Git remote `upstream` уже настроен; установка оригинального
ZIP или обновление из его p2-сайта не переносит наши изменения.

## Integration checkpoint: 2026-11-11

Integrated upstream `45593059` into the fork based on `70386336`, retaining both
histories (14 incoming upstream commits).

- Upstream batch `read_method_source` remains the base implementation. The fork's
  `methodHash` and saved-snapshot safeguards work for single and batch reads; native
  model reads remain available for declarations outside the text scanner.
- Keep the fork's existing migration epochs 23/24. Epoch 25 adds upstream's
  `standalone_server` restriction only to recorded Analysis Only / Code Review
  profiles, without resetting other owner choices. Recognizable upstream epoch-23
  stores receive the missing fork restriction. Old stores with indistinguishable
  provenance cannot be perfectly reconstructed; reapplying the intended preset
  restores its complete restrictions.
- Follow upstream's no-root-combinator schema contract for `write_module_source`.
  Runtime validation still rejects missing source/edits and incompatible combinations.
  For this compatibility adjustment, the merged 109-tool schema snapshot changes
  only by removal of this root `oneOf`.
- Retain fork update/release URLs and the existing BSL review/diff workflows.

Verification: clean canonical build on JDK 25: 13,650 Java tests, zero failures/errors,
25 skips; Python harness 315 tests and workflow scripts 5 tests passed; agent-pack
validator passed. Live BSL reader checks: 22 passed. Writer checks: 67 passed plus
2 vendor-protection checks passed after importing the vendor fixture; 2 environment
skips. Live schema snapshot passed. MCP conformance has no new baseline failures.

The complete E2E sweep was attempted in isolated EDT copies and is **not green**:
comparison/project reset stalled inside EDT; some checks assume absent platform
runtimes while this machine has 8.3 and lacks 8.5. Screenshot page restoration also
fails on the pre-integration fork bundle (70386336), so it is recorded separately
as an incidental defect rather than changed during this integration. These results
do not establish a fully verified release on every runtime/platform combination.

Local evidence: `/tmp/edt-sync-full-build-verified.log`, `/tmp/edt-sync-full-e2e/`,
`/tmp/edt-sync-conformance/final.log`, and `/tmp/edt-sync-baseline-ui/screenshot.xml`.
