# REA (Reverse Engineer Anything) — изолированный Android static research

## Назначение

Изолированный эксперимент GitHub Actions, не являющийся частью сборки BMW OCTANE. Для тестирования PR #52 временно разрешён узкий автоматический триггер `push` только в ветке `research/rea-actions-manual` и только при изменении workflow или проверочного скрипта; ручной `workflow_dispatch` сохранён.
Используется **официальный REA**: https://github.com/morluto/rea;
распространение через npm `rea-agents@6.5.0`.
REA работает на временном GitHub Linux runner и анализирует APK статически,
не запускает приложение, не обращается к DME и не меняет сборку Android.

Файлы:
- `.github/workflows/rea-analysis.yml` — `workflow_dispatch` + временный `push` для `research/rea-actions-manual` с фильтром двух исследовательских файлов;
- `scripts/research/rea/verify-public-apk.mjs` — реальные REA CLI проверки;
- `docs/research/REA.md` — этот документ.

## Совместимость и закреплённые версии

| Компонент | Используется | Источник |
|---|---|---|
| REA | `rea-agents@6.5.0` (npm exact) | https://github.com/morluto/rea |
| Node.js | 24.11.1 | REA поддерживает >=24.11 |
| JDK | Temurin 21, полная поставка | с модулем `jdk.compiler`; фактический patch записывается в Artifacts |
| OS | ubuntu-24.04 / x64 | GitHub-hosted runner |
| JADX | jadx-headless-mcp **0.7.1** | https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar |
| JADX SHA-256 | `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75` | https://github.com/morluto/rea/blob/main/src/android/JadxRelease.ts |
| fixture | Appium ApiDemos 6.0.18, Apache-2.0 | https://github.com/appium/android-apidemos |
| fixture SHA-256 | `a9eecf37b26cd084855c530db81c2bb1b91f4c1b095a04f47aa7c20e2791f686` | https://github.com/morluto/rea/blob/main/scripts/fixtures/android/apidemos.json |

Node.js, REA и JADX фиксированы точными версиями; JDK фиксирован major
и записывает фактическую patch-версию для каждой сессии. workflow проверяет
JDK-компилятор, procps `ps`, SHA-256 JAR и APK. REA запускается
с `REA_JADX_MCP_JAR` и ограничением Java heap/timeout. Отдельный `rea doctor`
сохраняется в диагностический отчёт, но только фактический результат операций
с APK может завершить работу со статусом PASS.

## Проверенный автоматический запуск (2026-10-17)

- **ПРОВЕРЕНО — GitHub Actions SUCCESS:** https://github.com/frundel-dev/bmw-enet-octane/actions/runs/39047573878
- SHA тестируемого кода: `c73dc364d6e78068b8adf3db60fb6e03a63ccddb`; событие `push`, исключительно ветка `research/rea-actions-manual`.
- **Artifact:** https://github.com/frundel-dev/bmw-enet-octane/actions/runs/39047573878/artifacts/12105514444, `rea-public-apidemos-39047573878` (30 файлов, 99 222 байта; из GitHub API и job log).
- Реальный лог содержит `PASS: real REA CLI/JADX APK, class inventory and onCreate method`; npm/JVM/JADX/fixture preparation и заключительный анализ завершились успешно.
- Проверены именно операции `inspect_android_package`, `search_android_classes`, `inspect_android_class`, `inspect_android_method`; итоговый отчёт `acceptance.json` и Evidence сохраняются в Artifact. Встроенный проверочный скрипт требует соответствующий APK SHA-256 и фактическое Java-тело.
- **Не проверено:** bootmod3, MG Flasher, большие multi-DEX APK и частная изолированная загрузка закрытых APK. Этот запуск не является испытанием автомобильных диагностических функций.

## Как запустить

**Важное ограничение GitHub:** `workflow_dispatch` может вызываться,
только когда сам файл workflow находится в **default branch** репозитория.
Пока файл добавлен только в исследовательскую PR-ветку, кнопка `Run workflow`
может отсутствовать. PR не следует объединять без отдельного решения
владельца репозитория. После ревью и разрешённого включения этого
изолированного файла в `main`:

1. Откройте https://github.com/frundel-dev/bmw-enet-octane/actions.
2. Выберите **Manual REA Android Static Analysis**.
3. Нажмите **Run workflow** и выберите `main` (или требуемый ref
   после регистрации workflow в default).
4. Откройте завершённый run. Скачайте ZIP **rea-public-apidemos-<run_id>**
   со страницы **Artifacts** (хранится 14 дней).
5. Проверьте `status.txt=PASS`, `acceptance.json` с `status=PASS`,
   а также `classes.txt`, `method-onCreate.java.txt` и JSON Evidence.

Для первого тестирования без объединения с `main`: изменение файла `.github/workflows/rea-analysis.yml` или `scripts/research/rea/verify-public-apk.mjs` в ветке `research/rea-actions-manual` запускает автоматический `push`-run; убедитесь, что загрузились реальные REA Evidence и итоговый статус `PASS`. Этот тестовый триггер нужно убрать отдельным коммитом после подтверждения работоспособности, оставив ручной запуск. Он не выполняет тест на произвольных APK и не действует при изменениях `main` или в других ветках.

**До первой завершённой реальной GitHub Actions проверки нельзя считать
REA установленным или Android-анализ подтверждённым.** Успех
`npm install`, `rea --help` или standalone JADX не заменяет эти проверки.

## Какие операции проверяются

Процесс запускает только официальную команду `rea` с `--format json`:
- `inspect-android-package`: настоящий APK, package name,
  версия и декодированный манифест;
- `search-android-classes`: полный перечень найденных классов,
  наличие `io.appium.android.apis.ApiDemos`;
- `inspect-android-class`: объявленный `onCreate`;
- `inspect-android-method`: Java-тело `onCreate` с фактическим
  `getIntent()` и `setListAdapter`.

Каждый ответ должен содержать `operation`, `normalized_result`
и SHA-256 **того же исходного APK**, записанные самим REA.
После завершения исходный APK хэшируется повторно. Ни одна команда
`jadx` отдельно не вызывается: JADX используется только как provider REA.
Положительные результаты сохраняют сырые JSON-доказательства.

В архив попадают версия Node/npm/JDK, npm-версия REA, `rea doctor`,
статус, APK/JAR SHA, список классов, Java-тело метода, собственные
REA evidence по каждому запросу, stderr и причины ошибок. При ошибке
workflow получает красный статус, но `upload-artifact` запускается
через `if: always()`. Если runner упал до создания каталога, важны
логи job и краткий GitHub Step Summary; успешный Artifact не подтверждает PASS.

## Безопасность и ограничения

- Repo публичный; его Actions logs и Artifacts могут быть доступны другим
  людям. Workflow **не принимает произвольный URL/APK на вход** и
  скачивает только публичный, разрешённый для теста ApiDemos.
- **Нельзя** помещать bootmod3, MG Flasher и их DLL/APK в публичный
  репозиторий или публичные Artifacts без отдельного согласования доступа
  и законности исследования. Будущие частные исследования потребуют
  отдельного контроля прав и среды — здесь это намеренно не реализовано.
- Read-only статический анализ, без ADB, запуска APK, неизвестных
  скриптов из APK, сети автомобиля, UDS, ENET, VXSCAN и ECU write.
- REA использует сторонний анализатор JADX. Верификация источника JAR —
  SHA-256 из официального REA, а не вывод об отсутствии у него уязвимостей.
- Результаты декомпиляции — производное представление, не полная
  спецификация семантики или доказательство поведения runtime.
- Нет MCP-сервера, постоянного runner или интеграции с ChatGPT.
- Приоритет задач APK-build CI, данные, алгоритмы топлива и пользовательские
  CSV не меняются; в рабочем `main` запуск исследования предусмотрен вручную; временный автоматический тестовый запуск ограничен одной исследовательской веткой.

## Как отменить

Пока PR не merged — закрыть экспериментальный PR; `main` не изменится.
После допустимого merge — удалить `.github/workflows/rea-analysis.yml`,
`scripts/research/rea/verify-public-apk.mjs` и этот документ отдельным
revert PR. Нет миграции приложения, версий Android или пользовательских данных.
