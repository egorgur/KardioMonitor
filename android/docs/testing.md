# Тестирование и проверка

Агент завершает работу, когда «выглядит готово». Тесты превращают «выглядит» в
проверяемый сигнал «прошло / не прошло» — поэтому **у каждой задачи должна быть
команда, которая её проверяет**.

## Команды

```bash
./gradlew testDebugUnitTest            # модульные тесты (JVM, без телефона), ~10 с
./gradlew testDebugUnitTest --tests 'ru.kardiomonitor.platform.huami.Huami2021Test'   # один класс
./gradlew lintDebug                    # статический анализ Android
./gradlew assembleDebug                # сборка APK
./gradlew connectedDebugAndroidTest    # инструментальные тесты на разблокированном телефоне
./gradlew installDebug                 # установка на телефон по adb
```

Полная проверка перед завершением задачи:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Отчёты: `app/build/test-results/testDebugUnitTest/*.xml`,
`app/build/reports/lint-results-debug.xml`.

## Что и как тестировать

| Уровень | Что | Как | Где |
|---|---|---|---|
| Домен | Правила предметной области: допустимый пульс, итоги дня, частота измерения | Чистые JVM-тесты, без моков | `src/test/.../features/<контекст>/domain/` |
| Слайс | Сценарий целиком через его точку входа (сценарий или ViewModel) | Рукописные фейки портов (`FakeActivityHistory`, `FakeBandConnection`) | `src/test/.../features/<контекст>/<слайс>/` |
| Протокол | Побайтная совместимость с браслетом | Эталонные векторы (golden tests) | `src/test/.../platform/huami/` |
| Архитектура | Границы слайсов и контекстов, проверяемые правила кода | Разбор исходников ([ADR-0005](adr/0005-architecture-tests-without-library.md)) | `src/test/.../architecture/` |
| Устройство | Обмен с настоящим браслетом | Ручная проверка по журналу `adb logcat` | — |

### Правила

1. **Проверяйте результат, а не реализацию.** Тест сценария смотрит, что сохранилось
   в `FakeActivityHistory` и что показано в `UiState`, а не какие методы вызывались.
   Порядок предпочтения: выходное значение → состояние → взаимодействие.
2. **Фейки вместо моков.** Рукописный `FakeActivityHistory` с `MutableList` внутри
   понятнее агенту, чем настройки Mockito, и не ломается при рефакторинге. Фейки
   портов лежат в `src/test/.../features/<контекст>/fakes/`.
3. **Протокол — только с эталонными байтами.** Любое изменение в шифровании,
   кодировании пакетов, форматах времени и записей сопровождается тестом с векторами,
   полученными из проверенной на браслете Python-реализации (`../huami2021.py`,
   `../fetch_history.py`) или из реального дампа. Векторы генерируются скриптом, а
   не вручную. Пример — `Huami2021Test`.
4. **Новое правило предметной области = новый тест** в той же задаче.
5. **Баг = сначала падающий тест**, воспроизводящий его, потом исправление.
6. **Архитектурный тест упал — исправляйте код, а не тест.** Ослабить правило или
   добавить исключение можно только изменив [architecture.md](architecture.md) или
   [coding-rules.md](coding-rules.md) с согласия человека.

### Имена тестов

Имя описывает поведение так, чтобы его понял врач или пациент, — фактом, а не
пожеланием, без имени тестируемого метода
(по Владимиру Хорикову, skill `test-namer`):

```kotlin
// Плохо
@Test fun parseSamples_hr255_returnsNull()
@Test fun `should return null for invalid heart rate`()

// Хорошо
@Test fun `пульс 255 означает, что замера не было`()
@Test fun `минута без браслета не учитывается в покрытии пульсом`()
@Test fun `повторная синхронизация не дублирует минуты`()
```

Для служебного кода без предметного смысла (кодировщики, шифрование) имя тоже
описывает результат, а не метод: `` `разбиение сообщения на части совпадает с эталоном` ``.
Пример — `Huami2021Test`. Правило проверяет `CodeRulesTest`.

Имена с пробелами — только в `src/test` (JVM). В `src/androidTest` пробелы в именах при
minSdk 26 не проходят D8 (нужен API 30+): там имя — camelCase на английском
(`heartRate255MeansNoMeasurement`), а поведение по-русски — в KDoc теста.

## Изолированная read-only проверка production Xiaomi core

Не запускать общий `connectedDebugAndroidTest`/`installDebug` на личном телефоне:
они могут установить приложения пациента/персонала. Для Band 9 используется только
library instrumentation APK `ru.kardiomonitor.bandprotocol.test`:

```bash
./gradlew :band-protocol:assembleDebugAndroidTest
# Из корня репозитория, SERIAL — выбранный разблокированный тестовый телефон:
node tools/band9-run-production-readonly.mjs SERIAL
# Два чтения одного past details ID; offset — только в свежем индексе:
node tools/band9-run-production-readonly.mjs SERIAL history_repeat 0
# Только с согласия владельца, если Mi Fitness перезапускается после force-stop:
node tools/band9-run-production-readonly.mjs SERIAL history_repeat 0 --pause-mi-fitness
```

Runner проверяет отсутствие активного probe, ставит только library test APK, временно
передаёт ключ через stdin в private storage тестового пакета, читает только auth/device
info/battery/GET HR settings production-клиентом и в finally удаляет файл ключа, завершает
read-only тестовый процесс и возвращает Mi Fitness. В выводе только безопасные статусы,
никаких адресов, ключей, показаний, raw-пакетов или истории. Наличие процесса Mi Fitness
**не подтверждает** её Bluetooth-переподключение — это отдельная проверка владельца.
По умолчанию history не запрашивается. Явный `history_repeat OFFSET` дополнительно
проверяет один past details-файл: exact ID/CRC/padding, повтор тех же байтов, агрегаты и
неизменность полного multiset индекса. Нет нормализованных минут, полного покрытия,
live/SET или ACK 8/5; успешный тест не закрывает клинический E2E.

`--pause-mi-fitness` — отдельное opt-in разрешение временно выполнить `disable-user`
только для Mi Fitness у пользователя 0. Runner сначала запоминает enabled-setting;
уже отключённый или неизвестный пакет отклоняется до установки APK и чтения ключа.
В `finally` восстанавливается именно исходный `default`/`enabled`, проверяется его
совпадение и запускается Mi Fitness. Ошибка восстановления делает cleanup неуспешным.
Данные приложения, bond и настройки браслета не очищаются. Это не защита от физического
отключения USB, аварии host-процесса или выключения телефона; после такого прерывания
сначала нужно отдельно восстановить Mi Fitness и проверить отсутствие временного ключа.

Фейки host-runner проверяются `node --test tools/band9-run-production-readonly.test.mjs`.

### Независимый эталон истории Band 9

Opt-in `history_reference OFFSET` использует тот же bounded repeat/retention, но дополнительно
сравнивает одну **независимо выбранную** минуту из tooltip Mi Fitness. `history_reference auto`
выбирает один ближайший предшествующий details ID из свежего past-индекса: поддержанный
minute-aligned epoch до signed/u32 расхождения, тот же signed quarter-hour offset и не далее
2880 минут. Нет fallback на предыдущие файлы, обхода архива или durable offset/cursor.
Выбор по гипотезе времени сам по себе не подтверждает время или покрытие выбранного файла.

Для сегодняшнего индекса существует отдельный явный `history_reference_today OFFSET`
или `history_reference_today auto`, с тем же JSON stdin и optional `--pause-mi-fitness`.
Он требует reference, не запрашивает past и не является fallback из `history_reference`.
Today GET8/1 включает health/options(field1=0); ID обоих ответов индексов — health field2.
Только details v4, два чтения одного exact ID и retention того же источника; summary,
ACK8/5, повторные попытки и автоматический обход запрещены. Schema3 проверяет согласие
`history_index` с запрошенным источником. В safe host report это `history_reference_index`.

В reference-режимах `history_diagnostics` содержит whitelist stage/selection, без исходных
ошибок/ID/времени/значений. `selection/no_preceding_file` означает отсутствие допустимого
descriptor start ≤ reference среди supported aligned same-zone кандидатов, а не HR mismatch
или доказанное отсутствие истории. `selection/empty` означает отсутствие details-кандидатов;
`stage=index` — индекс ещё не принят. Отказ до fetch не доказывает repeat/retention.

Дополнительный schema3 `history_selection_evidence` — наблюдение, не условие PASS:

- `reference_clock_relation`: future / within_5m / within_1h / within_24h / older;
- `candidate_clock_relation`: all_not_future / some_future / all_future;
- `after_reference_gap`: under_5m / under_1h / under_24h / at_least_24h;
- `after_reference_same_local_day`: boolean/null.

Clock — snapshot телефона перед history, не доказанные часы браслета. Candidate relation
считается только для supported aligned same-zone starts. Gap и local day относятся к
**самому раннему** start и доступны лишь если все такие starts позже эталона. Unknown
clock/empty candidates не превращаются в false/zero; отсутствующие/неизвестные значения
проецируются как null. Даты, числовые timestamps/deltas/offsets и raw fields не выходят.
Эта диагностика не разрешает fetch при no_preceding_file и не доказывает причину исчезновения
сегмента после синхронизации Mi Fitness; manual samples могут быть другой категорией.

[Capture helper](../../tools/band9-capture-reference.mjs) экспортирует
`captureMiFitnessReference({adb, apkPath, expectedDay, installApk = true})`. Это opt-in
чтение уже открытой пользователем точки Mi Fitness, без навигации/Bluetooth/ключей.
Дата expectedDay — ISO local day; в private capture-request передаётся через stdin.
Bounded accessibility traversal ограничен256 посещёнными узлами и только пакетом Mi Fitness;
null root ожидается до8 раз по250ms без waitForIdle. Два снимка с интервалом500ms должны
давать один reference; дата заголовка, tooltip HR/time и однозначность offset обязательны.
Parser зависит от текущей версии/разметки UI; missing/foreign/ambiguous окно даёт отказ,
не попытку принять summary/первый попавшийся BPM. Исторический offset по системной зоне
всё равно требует отдельного подтверждения при поездках.

Результат helper — safe `captured/stage/cleanup_verified` и **non-enumerable** `reference`.
Не выводить явно reference. Только при captured и cleanup_verified передавать его в
`runProductionReadOnly({adb, apkPath, historyReference: captured.reference,
selectReferenceTime: true, referenceIndex: 'today', pauseMiFitness: true})` либо явно past.
Если APK уже установлен перед открытием графика, `installApk:false` пропускает только
повторную установку, сохраняя проверки телефона/lock/private staging/cleanup. После capture
private request/output удалены и отсутствие проверено; эталон остаётся только в памяти.
Reinstall/возврат Mi Fitness могут закрыть график: повторно открыть его должен пользователь.
Helper не отключает и не запускает Mi Fitness; pause/restore принадлежат read-only runner.

Эталон — JSON через stdin (не аргумент shell/Intent), максимум 1024 байта, ровно поля
`epoch_seconds`, `utc_offset_seconds`, `heart_rate`, `steps`. Время — целая UTC-минута
0…2147483647, offset — секунды −43200…50400, кратные900. Пульс — null либо1…254,
шаги — null либо0…16383, хотя бы один показатель присутствует. `steps` — значение **этой
минуты**, не итог за день, не цель и не сумма столбца за интервал. Не выводить эталон в
терминал/отчёт/репозиторий; получать его до проверки, а не из декодируемого файла.

Runner валидирует эталон до любых ADB-действий, передаёт в private файл тестового APK через
stdin и удаляет его вместе с ключом в cleanup. Instrumentation также consumes/deletes его
до Bluetooth-сеанса и проверяет отсутствие в finally. Schema3 проецирует только булевы
проверки и nullable comparison: located, timezone, HR, steps. Вне файла HR/steps=null,
а не «не совпали». Сравниваются оба exact-ID/CRC/padding-checked чтения; prestarted/foreign
quarantine не получает record parser. Часовой пояс проверяется отдельно, повторно к epoch
не прибавляется. HR0/255 и отсутствующая validity не становятся измерением/нулём.

Совпадение одной точки не закрывает time/value/granularity/full-coverage gates: клинический
импорт остаётся запрещён. Для аппаратной сверки нужны несколько независимых точек/дней,
минутные steps, смысл HR0/255 и gaps. Snapshot телефона не доказывает исторический offset
в дату измерения; при поездке/смене зоны он должен быть подтверждён отдельно.

Файл эталона содержит чувствительные данные: после аварии host/USB отдельно проверить
его отсутствие, как и auth-key. Три synthetic instrumentation-теста `XiaomiHardwareReferenceTest`
работают только со своими UUID-файлами в isolated APK, без Bluetooth/ключа и клинических APK.

### Обратный контроль sample-before-reference Band 9

[Async host runner](../../tools/band9-run-reverse-reference.mjs) реализует отдельный явный
RAM-only эксперимент; прежние `history_reference*` остаются reference-first. Требуется
согласие владельца на временную паузу Mi Fitness и ожидание до5мин. Это библиотечный API,
не CLI с JSON/ключом в argv:

```js
const pending = runReverseReference({
  adb, startInstrumentation, apkPath, expectedDay,
  historyOffset: 0, pauseMiFitness: true,
  onSampleReady: safeStatus => notifyOperator(safeStatus),
});
// После подтверждения пользователя: только сигнал, не reference/readings.
adb(['shell', 'run-as', 'ru.kardiomonitor.bandprotocol.test', 'sh', '-c',
  "'umask 077; cat > files/reverse-capture.signal && chmod 600 files/reverse-capture.signal'"], 'capture');
const safeReport = await pending;
```

`adb(args,input)` синхронно возвращает захваченный stdout; `startInstrumentation(args)`
возвращает `{completed: Promise<string>, stop()}` только своего child-процесса. Оба транспорта
обязаны перехватывать stdout/stderr, а не печатать их или сохранять в host-файл. `stop`
завершает только принадлежащий вызову процесс. Не посылать сигнал сразу вслед за запуском:
сначала дождаться onSampleReady, затем отдельного подтверждения открытого пользователем
tooltip. Возврат callback не запускает capture автоматически.

Точный test — `XiaomiReverseReferenceHardwareTest#testSampleBeforeReference` в isolated APK.
Private reverse-request JSON `{day,offset}` (≤128B) и ключ проходят stdin и удаляются до
sample_ready. Один явно выбранный today details offset0…255 читается дважды, exact ID/
CRC/padding/byte equality и полный same-source retention обязательны. Данные удерживаются
только во внутреннем wipeable sample, не экспортируются/не сериализуются. **Offset не
означает самый свежий файл**; нет fallback, автоматического выбора другого offset/обхода.

После close Bluetooth Android записывает только safe readiness report. Host восстанавливает
исходный default/enabled Mi Fitness и запускает её до onSampleReady; test process нельзя
останавливать или переустанавливать APK до завершения сравнения. Пользователь синхронизирует
Mi Fitness и открывает независимую точку. Private сигнал — ровно ASCII `capture`, без LF.
За ≤300s от sample_ready он consumes/deletes; capture делает две устойчивые выборки tooltip
и сравнивает reference с RAM-образцом без нового Bluetooth-сеанса. Ни образец, ни reverse
reference на диск не пишутся. При timeout/ошибке явно обнуляются raw массивы; process death
прекращает владение памятью, но не позволяет заявить успешное сравнение.

Startup host≤90s, post-ready≤330s; они не продлевают Android300s или wire30s. Final schema1
требует sample verification/repeat/retention, client closed before reference, private cleanup,
reference capture+located+timezone+HR, `sample_memory_cleared` и instrumentation OK(1test).
Steps могут быть null. Safe `failure_stage=comparison` и `reference_record_located=false`
не являются числовым HR mismatch; before/after relation пока не различается. Финальный
cleanup удаляет/проверяет auth-key, reverse-request.json, reverse-capture.signal и owned
production-check.lock. reverse-result.json — только безопасные флаги, не история. После
аварии/USB-потери отдельно проверить cleanup/restore; исходные данные приложений не очищать.
Обнуление явных raw массивов не обещает стирание всех скрытых копий JVM/JCA/GC.

Тесты: `node --test tools/band9-run-reverse-reference.test.mjs`; без Bluetooth/key дополнительно
`XiaomiReverseReferenceHardwareTest#testSyntheticPrivateRequestAndSignalCleanup`. Никогда
не запускать весь hardware test class без явного намерения обратиться к браслету.

Полный пациент/персонал E2E — только фиктивный пациент и отдельный стенд; до подтверждения
history time/values и live stop соответствующие операции Band 9 явно недоступны.

## Проверка на телефоне

Телефон подключается по adb (USB или Wi-Fi). Приложение пишет обмен с браслетом
в журнал с тегом `KardioBLE`:

```bash
./gradlew installDebug
adb logcat -c && adb shell am start -n ru.kardiomonitor/.MainActivity
adb logcat -v time -s KardioBLE:* KardioServer:*   # браслет и сервер клиники
```

Что искать в журнале:

| Строка | Значит |
|---|---|
| `connection state 2, status 0` | Соединение установлено |
| `авторизация успешна` | Протокол Huami 2021 пройден |
| `write 2a39 140a -> status 0` | Браслет принял команду (частота пульса 10 мин) |
| `выгрузка истории с 2026-10-29T14:21+03:00…` | С какой минуты запрошена история: после последней сохранённой, не раньше привязки браслета (ADR-0011) |
| `синхронизация (фон): загружено N мин` | Фоновая синхронизация отработала |
| `KardioServer: отправлено врачу: N мин` | Новые минуты ушли на сервер клиники |
| `KardioServer: телефон зарегистрирован на https://…` | Регистрация на сервере прошла |

Оповещения клиники на Android 13+ проверяются отдельно: разрешить уведомления в карточке
«Оповещения клиники», выполнить синхронизацию после создания серверного оповещения и убедиться,
что оно появилось и в системной шторке, и в списке приложения. Повторное обновление с тем же
`alert_id` должно изменить значения списка, но не показать второе системное уведомление.
Для `training_minutes_exceeded` серверный снимок неизменяем: создать превышение в тестовом
аккаунте и проверить в списке и шторке день пациента, минуты и порог первого обнаружения.
Повторная отправка минут, рост/исправление итога, изменение порога и перезапуск приложения не
должны повторно уведомлять по тому же `alert_id`; после перезапуска снимок остаётся доступным.
Проверять на тестовом устройстве без очистки данных телефона пациента. Это отложенное
оповещение после синхронизации, не экстренная сигнализация.

JVM-регрессии оповещений (без устройства):

```bash
./gradlew :app:testDebugUnitTest --tests 'ru.kardiomonitor.features.clinic.data.*' --tests 'ru.kardiomonitor.features.alerts.*'
```

Проверяются разбор нового вида, сохранение снимка/курсора/отметок показа через JSON,
общий текст списка и уведомления, единственный показ после повторной доставки и перезапуска,
пропуск будущих неизвестных видов (в том числе целой страницы) без остановки пагинации.
`HttpClinicServerAlertsTest` использует локальный HTTP-сервер JVM и проверяет реальные запросы:
повторяемый `supported_kinds` со всеми пятью видами и `after` на каждой странице.

Запрос непрерывного пульса (ТР-09) проверяется на разблокированном телефоне с настоящим
браслетом:

1. В карточке «Запрос пульса от клиники» подтвердить обычную системную CDM-ассоциацию; в
   `adb shell cmd companiondevice list 0` должна появиться ассоциация `ru.kardiomonitor` с MAC
   браслета. Разрешить системные уведомления.
2. На панели создать запрос на 10 минут, затем выполнить ручную синхронизацию: состояние должно
   пройти `ожидает телефон → идёт`, а в шторке появиться постоянное уведомление с действием
   «Остановить».
3. Погасить экран. Значения должны продолжать приходить на общий график панели каждые 2–3 с;
   через 10 минут состояние — `завершён`, Worker и уведомление исчезли.
4. Создать второй запрос и нажать «Остановить» в уведомлении: сначала локально сохраняется
   `stopped_by_patient`, затем Worker отменяется; после появления сети панель показывает
   «остановлен пациентом».
5. Отдельно запретить уведомления и удалить CDM-ассоциацию: в обоих случаях BLE не запускается,
   запрос завершается понятной причиной, скрытого измерения нет. После проверки восстановить
   разрешение и ассоциацию.

APK устанавливается только через `adb install -r`: не удалять приложение и не очищать данные
телефона пациента.

Напоминания проверяются на телефоне отдельно от оповещений клиники: в карточке «Напоминания»
включить вид, короткое окно и интервал, разрешить уведомления и принудительно запустить работу
`patient-reminders` через jobscheduler. Проверить отдельные каналы трёх видов, действия
«Отложить на 30 мин» и «Не напоминать сегодня», отсутствие показа во сне, при выполненной норме
и при неполных данных интервала. Точные будильники приложению не разрешаются.

Фоновую синхронизацию можно запустить без ожидания 30 минут:

```bash
adb shell dumpsys jobscheduler | grep -A1 "ru.kardiomonitor"     # номер задачи
adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler ru.kardiomonitor 0
```

Данные приложения (debug-сборка):

```bash
adb exec-out run-as ru.kardiomonitor cat databases/samples.db > samples.db
adb shell run-as ru.kardiomonitor cat shared_prefs/band_settings.xml
```

Скриншот экрана (телефон должен быть разблокирован):

```bash
adb exec-out screencap -p > screen.png
```

## Ограничения

- Браслет держит **одно** соединение. Пока к нему подключены Mi Fitness, Python-скрипты
  на ноутбуке или другая копия приложения, тест на устройстве не пройдёт.
- Эмулятор Android не подходит для проверки обмена с браслетом — нужен телефон.
