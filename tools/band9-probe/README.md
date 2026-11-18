# Band9 Probe (изолированный диагностический APK)

Версия probe **0.32**, versionCode **32**, report schema **20**. Отдельный Gradle-проект, **не модуль основного Android-приложения**. Только platform Java Activity; applicationId `ru.kardiomonitor.band9probe`, compile/target 36, min 26, AGP 9.1.0, JDK 21. Внешних runtime/test dependencies нет. Используется существующий `../../android/gradlew`, без изменений android/.

Полный контекст для переноса в production: [план поддержки Band 9](../../docs/band9-implementation-context.md).
Диагностический probe не заменяет production-драйвер.

Продолжение 2026-11-18: independent reference-point harness добавлен **в library test APK**,
не в этот probe. Контракт stdin/private JSON и `history_reference OFFSET/auto` описаны в
[Android testing](../../android/docs/testing.md#независимый-эталон-истории-band-9).
Два offset0 repeat сохранили bytes/retention, но эталонная минута Mi Fitness оказалась вне
файла; auto-сеанс отказал на history-phase без завершённой сверки. Time/HR/steps не
подтверждены; clinical gates остаются закрыты. Probe0.32/schema20 не менялись.

Следующее продолжение локализовало past auto как `selection/no_preceding_file`, а в
отдельном сеансе с сегодняшним эталоном — `selection/empty`. В library APK добавлены
safe stage/selection, bounded direct UI capture и **явный** `history_reference_today`.
Today GET с options исправлен по существующему wire-контракту; аппаратный индекс получен,
но выбор тоже отказал `no_preceding_file` до fetch. Нет fallback, подгонки времени или
ослабления exact-ID/CRC. Это блокер независимой сверки, не доказательство потери истории.
Финальные106 protocol +19 architecture JVM,84 Node,4 synthetic instrumentation, lint/APK
PASS; подробности и следующий шаг — в [контексте](../../docs/band9-implementation-context.md#продолжение-2026-11-18-локализация-reference-selection).

Дополнительный library-only контроль `no_preceding_file`: schema3 coarse evidence
показал, что earliest supported daily start позже точки Mi Fitness менее чем на5мин,
при этом eligible starts не в будущем по часам телефона. Это **граница доступного окна**,
не доказательство удаления Mi Fitness или ошибки epoch. Selector/ID/CRC не менялись,
fetch не запускался; следующий контроль требует sample-before-sync acquisition.
Новые109 protocol +19 architecture JVM,87 Node и4 synthetic instrumentation PASS;
probe0.32/schema20 по-прежнему без изменений.

Следующий library-only эксперимент добавил
[RAM-only reverse runner](../band9-run-reverse-reference.mjs): один явный today offset0
повторён дважды до синхронизации Mi Fitness; exact bytes/retention PASS, затем socket close
и восстановление Mi Fitness перед независимым capture. UI-точка опять вне образца
(record_located=false, timezone=true, HR/steps=null). RAM очищена; offset0 не объявляется
самым свежим сегментом, причинная связь с синхронизацией не доказана. Нет auto retry/8/5.
113 protocol +19 architecture JVM,105 Node,5 synthetic instrumentation и lint/APK PASS.
Контракт — в [testing](../../android/docs/testing.md#обратный-контроль-sample-before-reference-band-9).
Probe0.32/schema20 не менялись; clinical gates закрыты.

## Что реализовано

| mode | Единственные действия |
|---|---|
| `transport` (по умолчанию) | Найти уже bonded устройство, secure RFCOMM SPP через SDP, один SPP-v1 version request, разобрать version response, закрыть socket. **Ключ не открывается.** |
| `auth` | То же; для version=2 открыть SPP-v2 session; Xiaomi protobuf 1/26 nonce → проверка watch HMAC → 1/27 encrypted confirmation. Затем закрыть socket, **никакой обычной инициализации Gadgetbridge**. |
| `read` | auth → только 2/1 battery и 2/2 device info → закрыть. Возвращает `partial`: отдельный безопасный read-only запрос today steps не подтверждён. |
| `live` | auth + battery/device info; только при model=`M2345B1`, firmware=`2.3.98` начать 8/45 realtime stats на 30 секунд, принимать 8/47, отправить 8/46 **в finally**, закрыть socket. |
| `settings` | auth + battery/device info; при точной модели/firmware **один GET 8/10**, сохранить только документированные HR settings с presence, закрыть. Никакого SET 8/11 или запуска HR. |
| `history_index` | auth + battery/device info; при точной модели/firmware **по одному запросу списков 8/1 и 8/2**; только counts/group summaries, без ID/дат/файлов. Никаких file request 8/3 или ACK 8/5. |
| `history_read` | auth + battery/device info; точный guard + SPPv2 → today index 8/1 → максимум восемь уникальных разрешённых daily файлов через 8/3 (сводка первой) → CRC32/ID/padding → безопасные агрегаты → повторный today index 8/1. **Никогда history ACK 8/5**, past, SET, live или raw-файлы на диске. |
| `history_sample` | Явный bounded sample: одна сводка и первый details-сегмент; дополнительно читает past index только как metadata, без 8/3. Отчёт всегда partial, пропущенные файлы считаются. |
| `history_sample_next` | Явный bounded sample только следующего уникального details-сегмента в порядке индекса; summary намеренно пропускается, past index читается только как metadata. Отчёт всегда partial. |
| `history_repeat` | `history_sample` плюс второй запрос того же первого ID в одном SPP-сеансе; сравнивает агрегаты и внутренний SHA-256 digest, digest не попадает в отчёт. |
| `history_batch` | Только явно выбранные 1–2 уникальных details-сегмента today, без summary; обязательны integer extras `detail_offset` 0–255 и `detail_count` 1–2. Past index — только metadata, полный today multiset проверяется после чтения. Всегда partial, без автоматического перехода к следующей порции. |
| `history_past_batch` | Такой же явный bounded range, но из past-индекса. Today читается только как metadata; после успешного чтения проверяется полный past multiset. Никаких автоматических обходов архива, сводок, ACK или клинического импорта. |
| `history_correlate` | Один явно выбранный past details ID (`OFFSET`, `COUNT=1`), ровно один 8/3; максимум два входящих файла до первого exact ID. Только CRC/ID/padding и безопасные признаки порядка/membership; **никакого parser/import**, повторного запроса или retention. Всегда diagnostic partial, даже при exact ID. |
| `history_target` | Один past details target (`OFFSET`, `COUNT=1`), ровно один 8/3; bounded quarantine с проверки индексов до retention. Только файл, начатый после запроса и имеющий exact ID/CRC/padding, допускается к aggregate parser; посторонние/ранее начатые — header-only и wipe. После target проверяется полный past multiset; всегда sample partial, не production sync. |
| `history_boundary` | Только один past8/2 и один today8/1, **без8/3**; bounded header-only quarantine при ожидании ответов. Фиксирует subtype на первом/последнем bulk, наблюдение transport ACK и deferred membership в обоих индексах. Всегда diagnostic partial; не доказывает отсутствие последующих данных. |
| `history_passive` | После auth + battery/exact identity одно абсолютное6s окно **до любых команд истории**. Только incoming transport ACK/header-only quarantine, без parser, ID snapshots,8/1/8/2/8/3/8/5/SET/live. Index subtype/ACK/membership=null. Всегда diagnostic partial, даже при нуле complete frames/files; не доказательство отсутствия потока. |
| `history_past_window` | Ровно один past8/2 и header-only наблюдение до/после его ответа в общем6s окне. Нет today8/1/fetch8/3/ACK8/5/SET/live; private past snapshot для membership/byte-difference diagnostics, today membership=null. Deadline не продлевается на ответ/traffic; всегда diagnostic partial, не source/quiet/clinical PASS. |
| `history_today_window` | Симметричный контроль: только today8/1 в одном6s окне. Past8/2/fetch/ACK/SET/live запрещены; отсутствующий past snapshot остаётся unknown. |
| `history_target_scan` | Past8/2 → today8/1 metadata → ровно один past8/3 (`OFFSET`, `COUNT=1`). До6 completed files после запроса; глобально до16 файлов/512KiB с начала index wait. Exact/CRC/pad без parser или retention; всегда diagnostic partial. |
| `history_past_target_scan` | Past8/2 → ровно один past8/3 (`OFFSET`, `COUNT=1`), **без today8/1**. Header-only до exact ID либо лимита16файлов/512KiB всего сеанса. Prestarted file не target; никаких parser/retention/ACK/SET/live. Exact остаётся diagnostic partial. |
| `history_past_target` | Past8/2 → ровно один past8/3 (`OFFSET`, `COUNT=1`) → exact-only daily aggregate parser → повторный past8/2 для полного multiset retention. Без today8/1, retry, ACK8/5, SET/live/import. Те же16files/512KiB на все стадии, включая retention. Всегда bounded sample/partial; время, значения и полнота не объявляются подтверждёнными. |
| `history_past_repeat` | Явный повтор одного past details ID (`OFFSET`, `COUNT=1`): past8/2 →8/3→8/3 **с тем же ID** →past8/2. Private SHA-256 и format aggregates сравниваются в памяти. Только два fetch, общий file deadline30s и прежние16files/512KiB на весьсеанс; несовпадающие файлы header-only. Partial даже при равенстве; no ACK/SET/live/import. |

Реализация оригинальная по фактам wire format из публичных исходников, исходники/сгенерированные классы Gadgetbridge не включены и не копировались. Включены собственные bounded proto2, SPP v1/v2, CRC-16/ARC, HKDF/HMAC-SHA256, AES-CTR и AES-CCM на JCA AES/ECB. Watch HMAC сравнивается constant-time; CCM MAC проверяется.

**Host-тесты не доказывают полную поддержку Band 9 в KardioMonitor.** На реальном M2345B1 / 2.3.98 с Galaxy S25 FE (Android 17) отдельно проверены SPP v2, авторизация с watch HMAC, заряд/идентичность и два 30-секундных потока HR. Результаты и оставшиеся ограничения — в [чек-листе](../../docs/band9-validation-checklist.md). `build_provenance` относится только к сборке; `hardware_attempted` устанавливается непосредственно перед `socket.connect()`, `rfcomm_connected` — после успешного соединения. Адрес и серийник не сохраняются. Отправка stop наблюдалась, но ACK остановки датчика не подтверждён.

## Сборка без изменений production

Из корня репозитория:

```sh
cd tools/band9-probe
ANDROID_HOME="$HOME/Android/Sdk" ../../android/gradlew --no-daemon --offline --console=plain :protocol:test :app:assembleDebug :app:lintDebug
node --test protocol/src/test/activity-safety.test.mjs
```

Если SDK находится иначе, задать свой `ANDROID_HOME`. При отсутствии Gradle/AGP в локальном кэше убрать `--offline` для первоначальной загрузки штатных build tools. SDK platform 36 должен быть установлен; JDK 21.

APK: `tools/band9-probe/app/build/outputs/apk/debug/app-debug.apk` (debuggable, стандартная debug-подпись, нужна для `run-as`). Lint: `app/build/reports/lint-results-debug.html`. Векторные тесты запускаются настоящим `:protocol:vectorTest` через JavaExec без JUnit, с ненулевым exit при ошибке; `:protocol:test` и `check` зависят от него. Пустой JUnit discovery отключён намеренно, тесты **не** пропущены.

## Подготовка и запуск оператором

Следующие команды — инструкция для родительского агента/оператора. Во время разработки они **не выполнялись**. Не затрагивать установленный `ru.kardiomonitor`, его данные или permissions. Существующая bonding обязательна; приложение не вызывает pair/unpair, BLE scan или включение BT. Mi Fitness может занимать SPP; управление им остаётся снаружи этого приложения.

```sh
# Из корня репозитория; установить ТОЛЬКО диагностический APK.
adb install -r tools/band9-probe/app/build/outputs/apk/debug/app-debug.apk

# Runtime grants (Android 12+, в частности API 37). Можно вместо этого подтвердить системный диалог.
adb shell pm grant ru.kardiomonitor.band9probe android.permission.BLUETOOTH_CONNECT
adb shell pm grant ru.kardiomonitor.band9probe android.permission.BLUETOOTH_SCAN

# Сначала transport: ключ и auth здесь не используются.
adb shell am start -W -S -n ru.kardiomonitor.band9probe/.ProbeActivity --es mode transport --es address AA:BB:CC:DD:EE:FF
```

`address` — реальный уже bonded BR/EDR адрес. Если extra отсутствует, выбирается **единственное** bonded устройство с именем `Xiaomi Smart Band 9 XXXX` (не Active/Pro); при неоднозначности — ошибка, не сканирование. С адресом используется точное совпадение в bonded set, не создание новой пары. Разрешение SCAN требуется только для `cancelDiscovery()` перед RFCOMM; discovery приложение не начинает.

Перед auth родительский процесс должен положить ключ в private `files/auth-key`: **ровно 32 ASCII hex символа, без LF**, то есть 16 декодированных байт. Никаких key extras, UI полей или логирования ключа. Не передавать ключ аргументом командной строки. Поддерживается поток от уже подготовленного безопасного producer:

```sh
# Левая часть потока подготавливается родителем; не печатать ключ и не включать shell tracing.
# Правая сторона потока (stdin — 32 hex символа от родительского producer):
# adb shell "run-as ru.kardiomonitor.band9probe sh -c 'umask 077; mkdir -p files; cat > files/auth-key'"

adb shell am start -W -S -n ru.kardiomonitor.band9probe/.ProbeActivity --es mode auth --es address AA:BB:CC:DD:EE:FF
# После завершения auth и анализа report:
adb shell am start -W -S -n ru.kardiomonitor.band9probe/.ProbeActivity --es mode read --es address AA:BB:CC:DD:EE:FF
# До восьми уникальных разрешённых today daily-файлов в память, без history ACK/сохранения сырых данных:
adb shell am start -W -S -n ru.kardiomonitor.band9probe/.ProbeActivity --es mode history_read --es address AA:BB:CC:DD:EE:FF
# Только чтение HR settings (прежний private ключ удалён: родитель должен заново безопасно stage его):
adb shell am start -W -S -n ru.kardiomonitor.band9probe/.ProbeActivity --es mode settings --es address AA:BB:CC:DD:EE:FF
# Только агрегированный индекс today/past, без скачивания и ACK:
adb shell am start -W -S -n ru.kardiomonitor.band9probe/.ProbeActivity --es mode history_index --es address AA:BB:CC:DD:EE:FF
# Только осознанный краткий live-сеанс (это команда запуска измерения):
adb shell am start -W -S -n ru.kardiomonitor.band9probe/.ProbeActivity --es mode live --es address AA:BB:CC:DD:EE:FF
```

Каждый запуск независимый, нужны свежие nonce/session. `-S` относится **только к probe** и нужен для нового Activity после завершения предыдущего. **Не запускать следующий `-S` во время live**: force-stop убивает процесс, Java finally физически не может выполниться. Дождаться финального report либо нажать **Cancel and close**. Уход из Activity вызывает отмену; в live отмена сначала выходит к finally/stop, затем закрывает socket.

```sh
# Финальный отчёт появляется после закрытия socket, не после возврата am start.
adb shell run-as ru.kardiomonitor.band9probe cat files/report.json
adb logcat -d -s Band9Probe:I '*:S'
# После завершения всех сеансов удалить private ключ:
adb shell run-as ru.kardiomonitor.band9probe rm -f files/auth-key
```

Отчёт перезаписывается атомарно; промежуточные `status:running` + `stage` сохраняются перед каждой фазой. Финальные `status` — `ok`, `partial`, `error`, `cancelled`; `code` — фиксированный безопасный код, не exception message. Log tag **Band9Probe**, только JSON events/results. Единственное исключение для диагностики wire: до auth, при получении ответа на version request, `version_response` содержит channel/opcode/data_type/payload_length и максимум **три числовых байта** `version_prefix`, только для channel=0 и длины payload≤64. Для любых protobuf/auth/других каналов prefix всегда пустой; callback существует только у `negotiate`, не у auth/read/live-пакетов. Полные raw packets, auth protobuf, key/nonce/HMAC/derived keys, адрес/серийник, exception stack traces не логируются. APK читает ключ только из private-файла для auth; [утилита подготовки](../band9-prepare-key.mjs) извлекает его из Mi Fitness локально в памяти и передаёт через stdin без вывода значения; чувствительные массивы очищаются best-effort, без обещания полного удаления JVM/JCA внутренних копий. Android backup и device transfer исключают private data; INTERNET/storage permissions отсутствуют.

## Ограничения и конечность

- В обычном `read` **нет отдельного today steps query**: он по-прежнему завершается `partial`, `today_steps:null`. Только явный `history_read` может вернуть steps из CRC/ID-проверенного daily summary v5 с точной известной длиной и присутствующим validity bit. В live сохраняется `realtime_steps` из 8/47, **не выдаётся за проверенные today steps**. Списки today/past в `history_index` не запускают скачивания.
- Нет записи interval/HR settings (8/11), history ACK (8/5), time/notifications/reset/unpair/user-profile/workout команд, произвольного/прошлого чтения истории, фонового сервиса, периодического battery polling, синхронизации/записи данных клиники. Live — единственное явно запрошенное изменение состояния измерения. В двух аппаратных прогонах первые HR появились через 20–22 с; события без HR не считаются измерением. Если валидных HR за 30 с нет, результат `partial/no_valid_heart_rate_samples`, даже если получены шаги.
- Общий watchdog **75 с от старта worker** закрывает socket; connect 12 с, version 6 с, auth 12 с, каждый read-query/index 6 с, каждый history file 30 с, write 3 с, live 30 с, finally-stop максимум 2.5 с. Все input deadlines ограничены общим абсолютным deadline 75 с, не продлеваются поступлением чанков. Read идёт коротким polling `available()`, не вечным blocking `read`. Все пути закрывают socket. `FLAG_KEEP_SCREEN_ON` установлен только на время активного diagnostic worker и очищается в `finishReport`; системный screen timeout не меняется. Уход Activity в фон/Cancel отменяет сеанс; history cancellation не отправляет команд ACK/удаления.
- Stop — **best effort**: `live_stop_attempted`/`live_stop_written` фиксируются отдельно, подтверждение stop не выдумывается (`live_stop_acknowledged:false`). При потере связи, убийстве процесса ОС/force-stop или питании stop не гарантируется. Нельзя считать socket close доказательством остановки датчика.
- При malformed/oversized frames, unsupported version, HMAC/MAC mismatch, NACK, sequence gap, unknown wire type — abort/close, **не guess/retry/unpair**. Version timeout не трактуется как SPP v1. Transport при version>2 не отправляет session/auth.
- SPP-v2 использует один небольшой конечный поток, ACK/duplicate handling, ограниченное окно, **без retransmit**: при потере кадров безопасная ошибка вместо повторов auth/control. Только session TLVs из опубликованного wire-format; negotiated extensions не угадываются.
- Опубликованный Xiaomi SPP-v2 шифрует protobuf AES-CTR с IV=key, заново на каждом сообщении. Это слабость самого wire protocol; CRC не MAC. Проект не заявляет криптографическую целостность SPP-v2 application data.
- Key-файл принимается строго: не binary 16 bytes, не `0x`, не whitespace/newline. Неправильный формат — безопасная ошибка до auth packet.
- На Samsung Android 17/API37 target36 устанавливаем, но реальную работу runtime permissions/RFCOMM и поддерживаемый протокол доказывает только ручной transport/auth отчёт, не host-тесты.

## Read-only settings/index (введены v0.5, поля сохранены в schema 4)

`settings` и `history_index` независимы: один не вызывает другой, оба сначала auth → battery 2/1 → deviceInfo 2/2. До любого нового health-запроса обязателен **точный** ответ model=`M2345B1`, firmware=`2.3.98`; проверка повторно обеспечена в Session, не только UI. Каждый health query имеет отдельный абсолютный deadline **6 с** и ограничение response/frame count; общий watchdog 75 с неизменен. Нет автоматической повторной попытки/синхронизации. Старые 30-секундный live, подсчёт валидных HR и допуск peer session version не изменены.

Общие поля сохраняют прежний смысл: `schema:4`, `probe_version:"0.6"`, `mode`, `status`, `code`, `stage`, `final_stage`, `elapsed_ms`, build/runtime provenance, результаты auth/device и счётчики кадров. В settings/index `today_steps` остаётся null. Ниже перечислены **все** новые mode-specific поля; null означает отсутствие/неприменимость, не значение по умолчанию.

### mode=settings

Root: `settings_status` = `not_requested` / `target_mismatch` / `query_pending` / `query_failed` / `cancelled` / `health_absent` / `heart_rate_absent` / `present`; `hr_settings` = null до успешного разбора, затем объект:

| Поля `hr_settings` | Тип/семантика |
|---|---|
| `query_type`, `query_subtype` | 8, 10; GET без health body |
| `command_status_present`, `command_status` | bool, uint32/null; поле Command 100 не подменяется нулём при отсутствии; ненулевой status — error |
| `status`, `health_present`, `heart_rate_present` | статус наличия `present`/`health_absent`/`heart_rate_absent`, два bool; Command.health=10, Health.heartRate=8 |
| `disabled` | bool/null, HeartRate field 1; отсутствие **не** трактуется как enabled |
| `interval_value` | uint32/null, field 2; исходное документированное число, не raw payload |
| `interval_unit` | `minutes` при положительном числе; `smart_sentinel` при 0; null если отсутствует |
| `interval_minutes` | положительный interval/null; 0 не выдаётся за нулевой период |
| `monitoring_mode` | `disabled` если disabled=true; `smart` или `periodic` только при явном disabled=false и наличии interval; иначе `undetermined` |
| `alarm_high_enabled`, `alarm_high_threshold_bpm` | bool/null, uint32/null; fields 3/4 |
| `advanced_monitoring_enabled` | bool/null, nested field 5 → 1; отсутствие nested/inner различается через presence |
| `alarm_low_enabled`, `alarm_low_threshold_bpm` | bool/null, uint32/null; nested field 8 → 1/2 |
| `breathing_score`, `irregular_rhythm_alert`, `sleep_respiratory_rate` | fields 9/10/11: `unsupported`=0, `on`=1, `off`=2, `unrecognized` для иного значения, null при отсутствии; неизвестное значение не раскрывается |
| `available_intervals`, `available_intervals_status` | null, `not_in_public_response_schema`; **никакого выдуманного списка 1/10/30** — схема не содержит capability list |
| `presence` | объект bool для каждого из 10 возвращаемых исходных scalar-полей выше (от disabled до sleep_respiratory_rate), плюс `advanced_monitoring` и `alarm_low` для наличия nested messages; ключ интервала — `interval_value` |

`interval_minutes` описывает сохранённый interval, **не доказывает активный период**, если disabled=true или disabled отсутствует. Настройки сна/алармов только читаются; пользовательские preferences не создаются и не меняются. Unknown field 7 и все недокументированные поля не выводятся. При отсутствии health/heartRate: `status:partial`, `code:hr_settings_absent`; при ошибке/timeout — error, `settings_status:query_failed`; до запроса — `not_requested` (или `target_mismatch`).

Пример отличия absence от smart: payload с одним `interval=0`, без disabled, даёт `interval_value:0`, `interval_unit:smart_sentinel`, `interval_minutes:null`, `disabled:null`, `monitoring_mode:undetermined`.

### mode=history_index

Root: `history_index_status` = `not_requested` / `target_mismatch` / `query_pending` / `query_failed` / `cancelled` / `index_field_absent` / `present`; `history_index_today` и `history_index_past` null до ответа либо объекты. `history_file_download_attempted:false`, `history_file_ack_attempted:false` во всех случаях. Каждый объект содержит ровно:

- `query_type:8`, `query_subtype:1` (today) или `2` (past).
- `health_present:bool`, `file_ids_present:bool`, `status:present|health_absent|file_ids_absent`.
- `file_id_count:int|null` — число 7-байтовых дескрипторов, **не** число измерений и **не** уникальное количество файлов между today/past. Ответы не дедуплицируются и могут пересекаться.
- `groups:[]` — агрегаты `{type:0..1, subtype:0..31, detail:0..3, version:0..255, count:positive int}`. Стабильная сортировка по flags/version, никаких имен/семантики неизвестных категорий не выдумывается.

До 256 ID (1792 bytes) на каждый ответ; длина кратна 7. Пустое присутствующее поле → count=0, groups=[]; отсутствующее поле → count=null. Invalid length/oversize/status abort/close, без повторов. Первые 5 байтов (timestamp/timezone) каждого ID **не декодируются**, полные ID нигде не materialize в результирующей модели, source массив очищается в finally даже при rejection; дополнительные копии bounded Proto/Frame очищаются при close. Списки ID, времена, raw/unknown payload, измерения/история/PII не логируются и не записываются.

Today request — **8/1**, Health.field5 → field1=0; past — **8/2** без Health body. В комментарии wear_fitness.proto номера сдвинуты: источником истины служат `XiaomiHealthService` constants и реальные builders. Response list — Health.field2 bytes. File ID: 7 bytes, категория из flags последнего байта (type bit7, subtype bits2..6, detail bits0..1), version из предпоследнего байта. В upstream file download и ACK идут отдельными командами 8/3 и 8/5; его fetcher здесь **не вызывается и не копируется**. Собственная реализация 8/3 доступна только в отдельном `history_read`. RFCOMM/SPP транспортный ACK нужен протоколу и не является file ACK 8/5.

Аппаратные settings и index прошли в v0.5 и повторно в v0.9/v0.15/v0.16: disabled=false, interval=0 (smart), today содержит разрешённые details v4; состав индекса меняется со временем. Внешний read-only runner удаляет временный ключ и восстанавливает Mi Fitness после каждого запуска.

## mode=history_batch / history_past_batch: явная ограниченная порция, schema 8

Безопасный runner принимает `node tools/band9-run-readonly.mjs SERIAL history_batch OFFSET COUNT`
либо `history_past_batch OFFSET COUNT` с теми же обязательными пределами.
Он принимает только probe 0.20/schema 8 с совпадающими параметрами и закрытым socket,
проверяет отсутствие активного production-check lock перед подготовкой ключа и печатает
только разрешённую проекцию отчёта (без числовой истории/ID/digest). После попытки удаляет
временный ключ и возвращает процесс Mi Fitness; Bluetooth-переподключение не доказано.
`history_requested_index_scope:today|past` показывает источник выбора; `retention_index_*`
относятся именно к этому источнику, не ко всей истории браслета. `history_past_batch`
передаёт только `8/2 → 8/1 → до двух 8/3 → 8/2` после auth/identity; today в этом режиме
не выбирается для fetch. Совпадение ID с другим индексом не заменяет совпадение с запросом.
`OFFSET` — нулевая позиция среди уникальных разрешённых details выбранного свежего индекса после
дедупликации, не позиция среди всех ID. Summary и неизвестные категории не скачиваются;
несколько различных summary по-прежнему считаются неоднозначностью и отклоняются.
Без обоих параметров, с неподходящим типом или диапазоном Activity отказывает **до
permissions, открытия ключа и Bluetooth**; другие режимы отвергают эти extras.

Каждый запуск заново получает индекс: offset **не долговечный cursor**. Даже неизменный
multiset при retention-проверке допускает другой порядок и другой выбор в следующем
сеансе. Сумма прочитанных порций не доказывает полное покрытие истории. Если offset
вне доступных details (включая пустой индекс), отказ `history_sample_offset_out_of_range`
до любого 8/3; если осталось меньше COUNT, скачиваются только оставшиеся, а
`requested_detail_count_satisfied:false` явно фиксирует неполную порцию. Отчёт содержит
запрошенные `detail_offset`/`detail_count`, фактически выбранное/пропущенное число файлов,
число details-кандидатов и `history_offset_scope:fresh_index_order_not_durable_cursor`.
`verified_sample` всегда даёт верхний `status:partial`, а не полную синхронизацию.

Аппаратный v0.17: offset 2 отсутствовал (отказ до fetch); отдельный GET показал
сменившийся состав: today=1 details + 1 summary, past=10 details. Batch 0/2 выбрал один
details, но полученный CRC-valid файл **не совпал с запрошенным ID**, не был разобран,
retention не достигнут. Двухфайловое чтение доказано пока только synthetic peer,
аппаратная проверка BLOCKED. Причина mismatch не установлена; подробнее в чек-листе.
Временные ключи удалены, socket закрыт, процесс Mi Fitness возвращён, reconnect не доказан.

### Проверка происхождения несовпадающего ID (v0.18–0.20)

В sample-режимах оба индекса временно остаются bounded/wipeable снимками в памяти,
включая unknown ID только для сравнения; они не становятся разрешением на download.
Отсутствующее поле индекса — ошибка, не пустой список. В отчёт входят только
`requested_index_scope`, `past_index_snapshot_checked`, `past_index_file_count`,
`received_id_in_original_index` (выбранный индекс), `received_id_in_today_index` и
`received_id_in_past_index`. Membership — bool/null: null до CRC или без снимка.
Все копии ID очищаются в finally, в том числе при ошибке второго индекса/файла.

После CRC дополнительно классифицируется лишь **арифметическое отношение** четырёх
байтов ID: `file_id_timestamp_relation` = `exact`, `byte_order_reversed`,
`plus_descriptor_timezone`, `minus_descriptor_timezone`, `same_minute`, `unclassified`.
Timezone-проверка использует signed byte × 900 и лишь одинаковое поле timezone;
это не доказательство времени измерений. Ни timestamp, ни offset, ни дата не
выводятся. В обычных read/batch любое не-exact ID всё равно abort, даже при понятном
арифметическом отношении. Отдельный header-only `history_correlate` ниже не запускает parser.

На железе v0.18 today 0/1 и v0.19 past 0/1 дали CRC-valid файл, отсутствующий в обоих
снимках. v0.20 past 0/1 снова не совпал; relation=`unclassified` — проверенные варианты
byte order / ±descriptor timezone / same minute его не объясняют. Parser не запускался,
retention не достигнут. Сохранность полного архива и причина mismatch **не доказаны**.
Никаких дополнительных hardware fetch после этой проверки ради перебора не делалось.

Проверки v0.20: **93 Java protocol tests + 28 Node safety tests PASS**, assemble/lint PASS.
Новые peer-сценарии проверяют past selection/retention, ошибку ID при попадании в другой
индекс, отсутствие/CRC-error и очистку снимков; это не аппаратный PASS двух файлов.

Прежние лимиты не изменены: 75 s всего, 30 s на файл, 512 KiB совокупно, 2048 chunks/файл,
2880 records/файл и максимум 256 ID. Никаких 8/5, SET, live, raw persistence,
автопрогрессии, обхода CRC/ID/padding или клинического импорта. Host-тесты включают
запрос двух различных файлов через scripted SPP peer, ошибку второго ID/CRC/padding,
изменение полного retention index, хвост и отказ до fetch для неверного offset.

### Корреляция незапрошенного потока (v0.21) и ограничители (v0.22)

`node tools/band9-run-readonly.mjs SERIAL history_correlate OFFSET 1` — отдельная
диагностика, не чтение клинической истории. Range проверяется до permissions/key/BT.
После past index и today metadata отправляется ровно один 8/3. На одном общем
30-секундном file-stage собираются максимум два файла; первый exact ID завершает
наблюдение. CRC/padding обязательны даже для несовпадающего заголовка; обычный
`verifyAndSummarize()` по-прежнему отвергает mismatch до parser. Header-only путь
никогда не разбирает HR/steps/время, не выполняет retention и не принимает чужой
файл за ответ. Нет retry, дополнительного fetch или автоматического продолжения.

Аппаратный v0.21/schema9: ещё при ожидании индексов приняты 10 bulk-кадров, до 8/3.
После единственного fetch пришёл CRC/padding-valid файл 34B, отсутствующий в обоих
снимках и не совпадающий с requested ID; transport ACK запроса ещё не наблюдался.
Следующий файл 1394B **точно совпал** с requested ID и past snapshot; transport ACK
уже наблюдался. Protobuf receipt 8/3 не наблюдался. Результат
`diagnostic_exact_after_unmatched`, оба parser=`not_parsed`, retention не проверен.
Это подтверждает посторонний поток в сеансе и опровергает «первый файл = ответ»;
источник/причина потока и корректный production demux ещё не доказаны.

В v0.22 бюджет **512KiB общий для всех channel5 payload в history-read сеансе**:
учитываются и пропущенные кадры при ожидании protobuf, и собираемые файлы, включая
4-byte chunk headers (без 2-byte SPP envelope); дубликат transport sequence повторно
не считается. Пропустить при ожидании protobuf можно не более **32 encrypted bulk
кадров суммарно**. Plaintext, превышение любого лимита, timeout/cancel — немедленный
отказ без retry; 30s correlation-stage не перезапускается после mismatch, общий75s
сохранён. Экспортируются только counts `bulk_frames_before_request` и
`bulk_payload_bytes_before_request`, ordinal, bool/null ACK/receipt/membership.
Runner принимает только v0.22/schema10, не прежний аппаратный отчёт v0.21/schema9.

Проверки v0.22: **99 Java protocol +30 Node safety tests PASS**, assemble/lint PASS.
Включены flood до fetch, plaintext, общий prefetch+fetch byte budget, CRC/padding,
два mismatch, поздний receipt, отсутствие второго файла, timeout/cancel с сохранением
первого безопасного заголовка и единственным stage/fetch. Новые ограничения проверены
на synthetic peer; аппаратный correlation выше относится к v0.21, а не v0.22.
Необходимо bounded demux/quarantine с exact ID, затем отдельная проверка retention,
времени/значений и покрытия. **Production history по-прежнему выключена**; 8/5 не
используется ни для очистки потока, ни для «разблокировки».

### Target-only demultiplexing (v0.23/schema11)

`node tools/band9-run-readonly.mjs SERIAL history_target OFFSET 1` — отдельный
probe-only режим с явным fresh-index offset и ровно одним target/fetch. Порядок
после auth/identity: **8/2 → 8/1 metadata → один8/3 → 8/2 retention**. Последний
запрос выполняется только после полного CRC/pad/exact-ID target; error/timeout/cancel
или два завершённых нетarget-файла не запускают retry/retention. Сводки не выбираются.
Прежние read/batch/correlate режимы не переключаются на новый алгоритм неявно.

`HistoryDemultiplexer` собирает файлы в памяти с первого index wait. Для постороннего
файла нет expected ID, parser недоступен даже при известной категории; после проверки
CRC/padding остаются только counts/category/membership и буфер очищается. Неизвестная
категория может быть quarantined, но не разобрана. Файл, начатый до8/3, остаётся
quarantine, даже если продолжается после команды или совпадает с будущим target ID.
Уже начатый файл нельзя сбросить новым chunk1; интерливинг/пропуск/плохая CRC/pad — отказ.
Это последовательный bounded demux, не реконструкция произвольного multiplexed потока.

После команды допускаются максимум **два завершения файлов до exact target**,
включая завершение prefetch partial. Общие limits: 512KiB channel5 payload с chunk
headers за весь history-сеанс; 32 bulk-кадра суммарно при protobuf wait; 16 completed
файлов суммарно; один30s target-stage, absolute75s; прежние chunk/frame limits.
Transport duplicate не повторяется в assembler/бюджете. Таймаут/отмена/ошибка сохраняет
только safe partial metadata, wipe выполняется и при сбое observer. Во время retention
новые bulk-файлы снова quarantine; неполный хвост на момент ответа index не позволяет
выдать verified sample. Это не ожидание бесконечного «тихого» потока.

Только exact requested target проходит обычный `verifyAndSummarize()` с агрегатами
presence/record counts. Экспортируются `demux_route` (`quarantined`, `requested_exact`,
`invalid`, `incomplete`), `demux_file_phase`, ordinal и fetch count, без raw ID/времени/
измерений. `verified_target_sample` всегда upper-level **partial**. Retention проверяет
лишь неизменность полного past multiset в этом сеансе, не полное покрытие/сохранность
архива. Неподдержанный layout и изменившийся index не становятся verified sample.

Host-проверки: **113 Java +32 Node tests**, assembleDebug/lintDebug PASS. Покрыты
pre-index exact/unknown/partial, mismatch→target, два mismatch без третьего чтения,
trailing bulk во время retention, plaintext/CRC/pad/order/channel, суммарный budget,
transport duplicate, timeout/cancel, wipe и отказ при observer failure.

Аппаратный v0.23: первоначальный preflight остановлен на screen lock; после того,
как пользователь разблокировал телефон и согласовал один прогон, обновлён только
isolated probe (без очистки данных) и выполнен **один `history_target 0 1`**.
Auth/exact model/firmware PASS, selected1 из10 past details. В index wait полностью
собраны и quarantined10 CRC/pad-valid файлов (868B суммарно), затем после одного8/3
ещё два файла418B и82B. Оба ID не совпали с requested и отсутствуют в обоих снимках.
Guard `history_target_not_received` остановил сеанс за928ms; все12 parser=`not_parsed`,
retention не запускался. Это PASS ограниченного отказа, **не PASS target read**.
Synthetic regression воспроизводит только форму10 prefetch +2 mismatch с новыми
фиктивными байтами/ID; queued третий target не читается. Реальный третий файл неизвестен.
Socket закрыт, временные ключи/lock отсутствуют, процесс Mi Fitness восстановлен,
Bluetooth reconnect не доказан; ACK8/5/SET/live/raw persistence не было.

**BLOCKED:** exact target не получен в выбранном двухфайловом окне; порядок/источник
потока неизвестны. Не повышать окно и не повторять запросы наугад. До следующего
hardware fetch нужна отдельная проверка правил маршрутизации/границы потока без8/5.
Production history/time/value/today steps остаются закрыты.

### Index-only границы потока (v0.24/schema12)

`node tools/band9-run-readonly.mjs SERIAL history_boundary` не принимает range extras.
После auth/identity выполняются **8/2 → 8/1**, по одному запросу; send-gate не допускает
8/3/8/5/SET/live. Оба index-stage по6s под absolute75s. Никакого ожидания idle/flush/
повтора/обхода архива: наблюдение завершается на втором index response, возможный
последующий bulk остаётся неизвестным. Верхний status всегда partial.

Новый `HistoryBoundary` — header-only quarantine, не ослабление target demux. Лимиты
прежние512KiB channel5 payload включая chunk headers,32 bulkframes,16 complete files,
строгий порядок chunks/CRC/pad/encrypted channel; transport duplicate не counted twice.
После CRC+pad временно сохраняются только7byteID (до16 копий), чтобы после обоих
индексов вычислить membership. Payload сразу wiped, deferred ID и snapshots — в finally,
включая timeout/cancel/observer failure. При отсутствующем snapshot или CRC/pad/partial
membership=null, не false. Доступ к ID после close запрещён host-проверенным guard.
Никакого parser, HR/steps/time/digest/raw persistence или экспорта ID.

Экспорт: `history_boundary` с counts и ACK-seen bool/null для каждого index response;
на файл — first/last bulk subtype, ACK-seen для соответствующего **текущего** index
request, ordinal, partial и deferred membership. Если файл пересекает два index-stage,
ACK-флаги относятся к разным запросам, subtype это явно показывает. ACK не доказывает
причину/принадлежность файла; наблюдение начинается после отправки первого index,
поэтому кадр в этом окне мог быть поставлен в очередь раньше.

Аппаратный v0.24: auth/exact identity PASS, **fetch_command_count=0**, оба index response
получены. Во время ожидания today8/1 пришли10 complete files,10bulkframes,868B payload
файлов/908B с chunk headers. Все CRC/pad PASS, все ID отсутствуют в обоих индексах,
все parser=`not_parsed`. Семь файлов622B пришли до transportACK today-запроса, три246B —
после; оба index responses наблюдались после своих transportACK. Нет partial хвоста
до второго response; это **не** доказательство тихого потока после него.
Код `diagnostic_index_boundary`, partial,1088ms; socket закрыт, ключ удалён, MiFitness
process восстановлен (BT reconnect не доказан). 8/3/8/5/SET/live не было.

Таким образом, unsolicited поток существует **без fetch8/3** и пересекает границу
transportACK. Отбрасывать только pre-ACK и считать post-ACK ответом небезопасно.
Причина/источник потока и его связь с index commands пока не доказаны; target-window2
не расширялось, новый target fetch не выполнялся. Публичный pinned
[XiaomiHealthService](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiHealthService.java#L1015)
подтверждает формы8/1,8/2,8/3, но его client-side очередь после index сама начинает fetch:
это не доказательство поведения браслета без fetch. Изученный
[chunk assembler](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileFetcher.java#L91)
не содержит request token в4-byte total/number header. Более слабые проверки upstream,
reset-on-chunk1, dumps и ACK сюда не переносились; исходники не копировались.

**121 Java +34 Node tests PASS**, assemble/lint PASS. Добавлен synthetic regression
формы10files/7preACK+3postACK/нетmembership с полностью фиктивными данными; это не запись
реальной истории. Последующий guard против чтения wiped ID после close проверен host,
аппаратный прогон ради него не повторялся. Production history/today/live/SET остаются
выключены; далее нужны правила source/routing, а не увеличение окна/ACK8/5.

### Пассивный контроль до index (v0.25/schema13)

`node tools/band9-run-readonly.mjs SERIAL history_passive` не принимает range extras.
Единственные application commands: auth1/26,1/27, battery2/1, deviceInfo2/2. После exact
model/firmware и SPPv2 guard все application sends запрещены отдельным gate; остаются
только transport ACK входящих SPP кадров, **не history ACK8/5**. Никаких index/fetch,
GET settings/SET/live/time, повторов или автообхода. Production код не менялся.

Один monotonic deadline=начало+6000ms, общий с Session и Activity, capped absolute75s.
Deadline не продлевается на traffic; session watchdog/ранний SocketTimeoutException,
EOF/cancel/CRC/pad/order/plaintext/channel/flood прерывают наблюдение. Только timeout
после этого deadline означает `diagnostic_passive_window_elapsed`; root всегда partial,
не clinical PASS. Нельзя назвать cutoff границей transport-frame: Wire мог остановиться
посередине SPP header/payload, поэтому `transport_boundary_at_deadline:not_verified`
и `absence_of_stream_proven:false` выставляются даже при нулевых счётчиках.
`incomplete_file_present` описывает только уже начатый assembler, не partial SPP frame.

В `history_passive` экспортируются лишь bounds/complete-frame/file counts/window flags.
Для файлов `boundary_observation_phase:pre_index_passive`, subtype/ACK/membership=null:
индекса/запроса не было, false/0 не подставляются. Quarantine сохраняет прежние лимиты
512KiB/32bulkframes/16files, strict CRC/pad/order и wipe в finally; verified ID copies
не создаются. Никаких parser, значений HR/steps/time, raw history или digest на диске.

**Один аппаратный прогон0.25:** auth/HMAC/exact identity PASS, окно6000ms истекло,
общий сеанс6909ms, `diagnostic_passive_window_elapsed`, partial. Наблюдалось0 complete
bulk frames/0bulk bytes/0complete files/нет начатого file assembler; index responses0,
history commands0, fetch0, download/ACK/retention=false. Это лишь отсутствие complete
bulk в данном окне данного сеанса. Вместе с0.24 оно **не доказывает**, что index является
причиной потока, что поток отсутствует дольше или что позже первый файл — target.

**128 Java +36 Node tests PASS**, assembleDebug/lintDebug PASS. Synthetic checks:
unsolicited/unknown/control/duplicate, silence, deadline посередине file/SPP header/
payload, ранний timeout/EOF/cancel, malformed/budgets/flood, no commands/ID copies,
observer failure/wipe и exact identity guard. Сырые данные браслета не использовались.
Socket закрыт, временные ключи обоих packages и production lock отсутствуют, MiFitness
process восстановлен; Bluetooth reconnect не доказан. Production history/today/live/SET
по-прежнему заблокированы; для продолжения нужны source/routing evidence, не ACK8/5.

### Изолированный past index с post-response окном (v0.26/schema14)

`node tools/band9-run-readonly.mjs SERIAL history_past_window` не принимает range.
После auth/battery/exact identity/SPPv2 выполняется **ровно один8/2**; отдельный
одноразовый send-gate запрещает любой дополнительный application send, включая8/1,
повтор8/2,8/3,8/5,SET/live. Incoming transport ACK не запрещены и не удаляют историю.
Один monotonic deadline6000ms начинается до команды, Activity использует тот же deadline
под absolute75s; ответ index и последующий traffic не продлевают окно. Наблюдение не
останавливается на ответе, но и не drain-until-idle. Missing/invalid/wrong/повторный index,
ранний timeout/EOF/cancel/malformed/flood — ошибка; недоставленный index не становится
пустым списком. Валидный пустой index остаётся пустым snapshot, не отсутствующим.

`history_past_window`: command/index/fetch counts, `bulk_frames_waiting_index` и
`bulk_frames_post_index_response`, window/status/ACK metadata. На файл — first/last
фаза `index_wait`/`post_index_response`, subtype2, ACK-seen и bool/null past membership;
today membership всегдаnull. Файл, пересекающий ответ, имеет разные first/last фазы.
Last-bulk metadata относится к последнему реально принятому chunk, не текущему stage
при close. Header-only quarantine сохраняет512KiB/32bulkframes/16files, CRC/pad/order/
encrypted channel и wipe; private verified IDs/snapshot стираются после membership.
Нет parser/HR/steps/time/raw persistence/клинического импорта или retention-проверки.
Cutoff SPP frame по-прежнему неизвестен (`transport_boundary_at_deadline:not_verified`),
`absence_of_stream_proven:false` даже при истёкшем окне и отсутствии partial assembler.

**Один hardware прогон0.26:** auth/HMAC/exact identity PASS, root partial,
`diagnostic_past_window_elapsed`,6000ms окно/6953ms сеанс. Один index response после
transportACK, history commands1 (8/2), fetch0. Получено10 complete bulkframes/10files,
868B filedata/908B с chunkheaders: **все после past response**, все CRC/pad valid,
все IDs вне past snapshot; today не запрашивался и membership=null. Все not_parsed,
нет partial file assembler, download/ACK/retention=false. Socket закрыт; keys обоих
packages/production lock absent, MiFitness process восстановлен; BT reconnect неизвестен.
Только isolated probe обновлён, приложения/данные пациентов/сервер не затронуты.

Это исключает today8/1 и fetch8/3 как необходимые команды в **этом** наблюдении.
Граница past response (как и ACK) не очищает unsolicited поток. Не доказаны причинность
8/2, источник/время формирования файлов, соответствие прежним сеансам или отсутствие
более поздних данных; совпадение count/bytes не доказывает одинаковые IDs/содержимое.
Target-window2 не расширялся, target fetch не повторялся,8/5 не посылался.

**135 Java +38 Node tests PASS**, assemble/lint PASS. Synthetic tests: before/after/
spanning response, ACK ordering, duplicate transport/index, nullable membership,
missing/invalid/wrong index, one-send gate/observer failure/wipe, partialfile/SPP
header/payload, early timeout/EOF/cancel, malformed/flood/bytes bounds и exact identity.
После hardware добавлена регрессия формы10post-response files/908B/outside past snapshot
с полностью фиктивными ID/байтами; аппаратный прогон ради неё не повторялся.
Production history/today/live/SET остаются BLOCKED: нужны подтверждённые routing/time/
value/coverage semantics, а не принятие любого post-response файла.

### Проверка wire ID и запрет использования очищенной памяти (v0.27/schema15)

Исторический этап без новых команд/режимов и **без аппаратного сеанса**. На этом этапе
runner принимал только0.27/schema15, чтобы не принять старый аппаратный отчёт0.26 за
проверку новой сборки. На телефоне0.27 не устанавливался; тогда последний hardware
результат был past-window0.26 выше. Последующие проверки0.28–0.29 описаны ниже.
Production-код и строгий exact-ID gate не менялись.

Независимо сверены факты pinned Gadgetbridge a047f5a1fe3067f3c685dca394b734bc11b79248:

| Wire-факт | Источник | Проверка нашего кода |
|---|---|---|
| Command.health — поле10; Health.activityRequestFileIds — одно bytes-поле2, не repeated protobuf message | [Command schema](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/GBProtobufGenerator/src/main/proto/xiaomi.proto), [Health schema](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/GBProtobufGenerator/src/main/proto/xiaomi/wear_fitness.proto) | Литеральный synthetic ответ8/2 содержит2 последовательных ID без count/length-префикса внутри bytes; оба сохраняются byte-for-byte после закрытия Proto |
| Index содержит конкатенацию7byte IDs, не отдельные length-delimited записи | [HealthService handleActivityFetchResponse](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiHealthService.java#L1065) | Границы7bytes и точная принадлежность snapshot без сдвига/byte reversal/timezone normalization |
| ID —4 timestamp bytes little-endian +1 timezone byte +1 version +1 flags | [ActivityFileId](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileId.java#L94) | High-bit timestamp/timezone bytes не преобразуются;8/3 сохраняет выбранный7byte ID точно; изменение любого из7bytes не принимается |
| Chunk prefix4bytes total/number; собранный file ID расположен в bytes0..6, padding byte7, CRC32LE последние4bytes | [ActivityFileFetcher](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileFetcher.java#L93) | Смещение0 подтверждено; file/header-only quarantine не становится exact target от одного CRC |

Комментарии schema о номерах команд местами не совпадают с действующими constants/
handler/send в HealthService; номера проверены по исполняемым веткам, не комментариям.
Это wire-контракт, не независимая аппаратная проверка абсолютного времени/значений.
Исходники/сгенерированные классы не копировались. Оснований менять offset или ослаблять
exact-match не найдено; источник аппаратного mismatch этим исследованием не установлен.

Найден и воспроизведён отдельный lifetime-дефект: послеclose ранее можно было сравнить
CRC-verified файл со стёртым нулевым ID, вычислить digest стёртого buffer или получить
равенство двух очищенных snapshot. Regression сначала упал на этих проверках. Теперь
HistoryFile запрещает послеclose accept/verify/membership/digest/summary, HistorySelection
— ID/count/membership/retention (с обеих сторон); `IllegalStateException` вместо ложного
true/false/0. Закрытие остаётся идемпотентным и стирает память. Ранее созданные безопасные
summary maps не зависят от raw buffer. Closed-file guard действует даже при пустом
snapshot. Quarantine ID-copy guard сохраняется. Это не признание данного дефекта причиной
hardware mismatch: такое участие не показано, прежние результаты не переобъявляются.

**139 Java +38 Node tests PASS**, assembleDebug/lintDebug PASS. Новые fixtures целиком
синтетические: literal protobuf/7byte identity/изменение каждого byte/owned copies и
closed-object rejection. Никаких8/1/8/2/8/3/8/5/SET/live/установки APK на этом этапе.
Production history/today/live/SET и полная поддержка остаются BLOCKED.

### Варианты mismatch и exact target без today-индекса (v0.28–0.29)

Новые CLI: `history_today_window` без range, `history_target_scan OFFSET 1` и
`history_past_target_scan OFFSET 1` через безопасный runner. Последний выбирает один
past details ID и не запрашивает today index: дополнительный поток от второго индекса
не смешивается с наблюдением target. Транспортные ACK остаются включены, historyACK8/5
не отправляется. Один file-stage30s, общий watchdog75s; нет retry/reconnect/обхода архива.
Глобальные лимиты16files/512KiB включают всё наблюдение, в том числе prefetch и chunk headers.

Private snapshots позволяют вывести только минимум количества различающихся ID bytes
и7bit mask их позиций (bit0..3 — timestamp bytes, bit4 — timezone, bit5 — version,
bit6 — flags). Это не значения ID или дат. При пустом индексе значения null; при нескольких
равно близких ID с разными masks mask=null и ambiguity=true, произвольный ID не выбирается.
Маска не разрешает нормализацию или принятие mismatch. Boundary дополнительно фиксирует
bool повторения ID внутри одного окна; retained IDs очищаются. Межсеансовое равенство
файлов не проверяется и не выводится из одинаковых counts/bytes.

**Аппаратные результаты0.28, по одному сеансу каждого варианта (2026-11-13):**

| Вариант | Наблюдение |
|---|---|
| Today-only | 6s окно/6912ms сеанс;7complete files,973B bulk payload/945B file data, все после response/ACK, все вне today snapshot; повторов ID в окне нет |
| Target scan offset0 | 881ms;10pre-request +6post-request files,1763B file data; все6post-request mismatch, `history_target_not_received` |
| Target scan offset1 | 1169ms;10pre-request +6post-request files,1456B file data; все6post-request mismatch, тот же отказ |
| Past-only контроль | 6s окно/7047ms сеанс;10post-response files,908B bulk payload/868B file data; все вне past snapshot, nearest masks только2/3 (timestamp bytes), повторов ID нет |

**Аппаратные результаты0.29:** два независимых `history_past_target_scan`, offset0 и1.
В каждом получены10mismatch, затем **11-й fresh-after-request файл — exact ID/CRC/pad**,
входит в past snapshot, expected-ID offset0. Offset0:852ms,1394B target/2262B всех файлов;
offset1:937ms,98B target/966B всех файлов. Today membership=null — индекса не запрашивали.
Все файлы во всех шести сеансах CRC/padding valid и `not_parsed`. Retention не выполнялся,
ни time/value semantics, ни полнота истории этим не подтверждены.

Это проверенный диагностический порядок, а не установленная причина потока: результаты
совместимы с посторонними передачами перед target, но их источник и семантика не доказаны.
Одновременно исключён today-запрос и увеличен post-request lookahead внутри прежнего
общего бюджета: вклад каждого изменения по отдельности этими сеансами не установлен.
Next step — отдельно подтвердить retention/time/value/coverage с тем же строгим demux;
production history/today/live/SET остаются выключены. **148 Java +42 Node PASS**,
assembleDebug/lintDebug/editor PASS.0.28 и0.29 устанавливались только поверх isolated probe
без очистки данных. После каждого сеанса временный ключ удалён/socket закрыт/Mi Fitness
process возвращён; финально оба временных keyfiles иproduction lock отсутствуют. Bluetooth
переподключение Mi Fitness не подтверждено. Ключи/ID/raw history не выводились и не выгружались.

### Past-only пульс/шаги: exact aggregate и retention (v0.30/schema18)

CLI: `node tools/band9-run-readonly.mjs SERIAL history_past_target OFFSET 1`.
После auth/battery/exact model+firmware и SPPv2 выполняются только **8/2 → один8/3 →8/2**.
Порядок уже проверен header-only в0.29; теперь parser вызывается только для fresh-after-request
exact ID/CRC/padding. Предшествующие, несовпадающие и retention-файлы только header-only.
Повторный past snapshot сравнивается целиком как multiset, включая неизвестные категории,
порядок не важен, потеря/дубли обнаруживаются. Неполный assembler/ошибки CRC/pad/budget/
отсутствующий или invalid индекс не могут дать verified sample. Неизвестный parser layout
остаётся `parser_unsupported`, без HR/step counts, притворяющихся нулями.

Глобальный бюджет16files/512KiB не сбрасывается на target или retention;30s file stage,
6s index stage и75s session cap остаются. Retention — только сравнение двух snapshots,
не доказательство отсутствия последующих кадров, сохранности всех данных или quiet boundary.
`verified_target_sample` остаётся **partial**; `history_time_values_verified=false` и
`history_full_coverage_verified=false`. Нет clinical upload, durable cursor или watermark.

**Два hardware0.30 сеанса, 2026-11-13:**

| Fresh offset | Сеанс | Exact target | Безопасный aggregate | Retention |
|---|---|---|---|---|
| 0 | 1403ms | ordinal11,1394B,daily_details_v4 | 86records,86HR-field-present/0HR-field-missing,86step-field records | Полный past multiset unchanged |
| 1 | 1410ms | ordinal11,98B,daily_details_v4 | 5records,5HR-field-present/0HR-field-missing,5step-field records | Полный past multiset unchanged |

В каждом10mismatch передtarget и3header-only files при retention:14files всего;
4703B и3407B file data соответственно. Все14 CRC/padding valid, только ordinal11 parsed;
received-ID входит в исходныйpast, today membership=null. В каждом индексе10detail candidates,
выбран1/пропущено9; offset свежего индекса не durable cursor, между сеансами ID не сравнивались.
HR presence — **только documented validity bit**, не сверка измерения и не утверждение
непрерывного пульса. Step records — присутствие поля, не сумма шагов. Время/значения/
гранулярность/пропуски/полнота ещё требуют независимой проверки.

**157 Java +44 Node PASS**, assembleDebug/lintDebug/editor PASS. Послеhardware добавлены
synthetic shape14files/ordinal11/3retention и aggregate prefetch-budget/cancel regressions;
это фиктивные байты, не raw history. Hardware ради тестов повторно не запускался.
Обновлён только isolated APK install-r безclear. После обоих сеансов socketclosed,
auth-key absent/MiFitnessprocessrestored; финально оба temporary keyfiles иproductionlock
отсутствуют. MiFitness Bluetooth reconnect не подтверждён. ACK8/5/SET/time/live/reset/
unpair/import/rawhistory persistence и публикации ключей/ID/персональных значений не было.
Productionhistory/today/live/SET остаются выключены. Следующее — независимая проверка
absolute time/timezone и HR/step value semantics, затем coverage и production integration.

### Диагностика времени и кодирования значений (v0.31/schema19)

Режим `history_past_target OFFSET 1` и wire-порядок не изменены. Только fresh exact
ID/CRC/pad получает новые aggregate-поля; quarantine/retention остаются `not_parsed`.
Часы телефона передаются parser только для этого режима, без записи времени в браслет.
Runner отклоняет старые отчёты вплоть до0.30/schema18.

Сверены pinned public источники Gadgetbridge commit
`a047f5a1fe3067f3c685dca394b734bc11b79248`:
[DailyDetailsParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/DailyDetailsParser.java),
[XiaomiComplexActivityParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/XiaomiComplexActivityParser.java),
[XiaomiActivityFileId](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileId.java).
Это reference implementation, не независимая приёмка данных M2345B1/2.3.98; код не копировался.
Group0: validity младших14бит — nibble bit0; extra-byte flag — bit14 packed word при
nibble bit1. Group4: validity — nibble bit2, значение unsigned byte. В upstream HR byte
не фильтруется по0/255: **семантика этих кодов остаётся неизвестной**, нельзя переносить
правило stress255 на HR. Здесь публикуются только количества кодов, не сами измерения.

| Поля | Значение и предел доказательства |
|---|---|
| `hr_encoded_zero_records`, `hr_encoded_255_records` | Число таких байтов только при установленном validity bit; не число подтверждённых измерений/пропусков |
| `step_encoded_zero_records`, `step_encoded_nonzero_records` | Категории младших14бит при steps-valid; не сумма шагов и не проверка точности |
| `value_interpretation_status` | Всегда `source_encoding_only_not_measurement_validation` |
| `descriptor_minute_aligned`, `descriptor_timezone_in_civil_range` | Делимость timestamp на60 и timezone в диапазоне UTC−12…UTC+14; не timezone/time correctness и не reject-gates |
| `descriptor_signed_unsigned_epoch_agree` | Совпадают ли signed/unsigned интерпретации первых4байт. Upstream использует signed int; наш unsigned вариант только candidate, не сертификация дат после2038 |
| `candidate_time_basis`, `time_mapping_status` | `unsigned_epoch_seconds_no_timezone_adjustment`, `source_hypothesis_not_independently_verified`. Upstream начинает с descriptor epoch и прибавляет минуту на запись; timezone вторично не применяется |
| `reference_clock_available`, `reference_clock_verified` | Наличие положительного wall-clock snapshot телефона; verified всегдаfalse, синхронность телефона/браслета не доказана |
| `candidate_last_record_not_future` | Последняя запись candidate timeline не позже snapshot; null при отсутствующих часах/пустом файле |
| `candidate_epoch_age_band` | Только `future/within_24h/within_7d/within_31d/older/reference_unavailable`; границы не являются критериями корректности/retention |
| `time_granularity_seconds` | 60 по public layout, не измеренная частота HR |

В неподдерживаемом/оборванном layout новые counts/time-поля отсутствуют, неизвестное не
становится нулём. Raw IDs, даты, timezone offset, clock snapshot и individual HR/steps не
попадают в report/CLI. Нет загрузки в clinical backend, смены watermark или изменения gates.

**Hardware0.31, два fresh-сеанса:**

| Offset | Сеанс/target | Encoding aggregates | Candidate time / retention |
|---|---|---|---|
| 0 | 1060ms, ordinal11,1394B,86records | 86HR-valid:78encoded0/0encoded255;82zero-step/4nonzero-step fields | minute-aligned/civil-zone/signed-agree;within_24h;last-not-future;full past multiset unchanged |
| 1 | 1276ms, ordinal11,98B,5records | 5HR-valid:5encoded0/0encoded255;5zero-step/0nonzero-step fields | Те же candidate checks;full past multiset unchanged |

Это выявило, почему прежние86/5 HR-present нельзя называть86/5 измерениями: установленный
validity bit допускает encoded0. Нельзя импортировать такой HR как0bpm или объявлять его
подтверждённым missing sentinel. Оба сеанса overallpartial, time_values_verified=false,
full_coverage_verified=false. По10quarantined→exact→3retention,14files; parsed толькоordinal11.
Root cause постороннего потока и стабильностьoffset междусеансами не установлены.

**164 Java +45 Node PASS**, assembleDebug/lintDebug/editor PASS. Synthetic tests покрывают
unsigned/signed boundaries и uint32 end, ±quarter-hour zones без double offset, minute/age
edges, unavailable clock/empty records→null, zero/255/absent validity,14-bit flags/extra-byte
alignment, unsupported layout, exact-only clock reads и no-private-data projection.
После обоих сеансов socketclosed/keyabsent/MiFitnessprocessrestored; финальнообаkeyfiles и
productionlock absent. BT reconnect MiFitness не проверен. Телефон сначала был заблокирован;
hardware запущен только после ручной разблокировки пользователем. ACK8/5/SET/time/reset/
unpair/clear/live/raw persistence не выполнялись. Следующий барьер — независимая сверка
времени/значений и семантикиencoded0, затем coverage/repeat и production integration.

### Повтор exact past-файла в одном сеансе (v0.32/schema20)

CLI: `node tools/band9-run-readonly.mjs SERIAL history_past_repeat OFFSET 1`.
Это отдельный opt-in режим: существующий `history_past_target` делает только один fetch.
После auth/exact identity/SPPv2: **past8/2 → past8/3 → past8/3 → past8/2**. Второй fetch
содержит те же 7 байт из исходного snapshot, без нового выбора offset или индекса. Он
разрешён только после полного первого fresh exact/CRC/pad и supported daily_details_v4.
Unsupported первый файл останавливает повтор; unsupported второй не даёт verified sample.
Нет автоматического третьего fetch, нового сеанса или расширения budgets при mismatch.

- Общие 16 complete files/512KiB включают index wait, оба fetch и retention; бюджеты не
  сбрасываются. Один абсолютный file deadline 30s и 4096 file messages на обе передачи;
  32 bulk на protobuf wait и 75s session cap сохранены.
- Первый проверенный digest хранится только в RAM; второй сравнивается через
  `MessageDigest.isEqual`. Первый digest очищается после сравнения либо в finally при
  ошибке/cancel/observer failure. Digests, IDs и raw bytes не сохраняются и не выводятся.
- `repeat_read_count` — число завершённых exact-чтений, не команд fetch. Поля
  `repeat_bytes_identical` и `repeat_aggregate_consistent` остаются null, если общий путь
  comparison/retention завершился исключением. Aggregate equality сравнивает только
  parser/counts/encoded categories/granularity, не wall clock или transport chunks.
  Одинаковые counts не означают одинаковых bytes; digest сравнивается отдельно.
- `fetch_command_count` показывает 1/2; фаза второго чтения — `repeat_wait`. Несовпадающие
  и ранее начатые файлы не разбираются; parser разрешён только для fresh-assembler exact
  target. Уже находящийся в очереди, но ещё не начатый assembler-файл неотличим от нового.
- Changed bytes дают `repeat_file_changed`, изменённый индекс — `retention_index_changed`,
  unsupported второй файл — `parser_unsupported`. Успех `verified_target_repeat_sample`
  остаётся partial, time_values_verified=false/full_coverage_verified=false.

**Hardware 0.32, 2026-11-13, два сеанса offsets 0/1:**

| Offset | Сеанс | Два exact target | Сравнение / retention |
|---|---|---|---|
| 0 | 1118ms | ordinal 11 и 15; по 1394B/86 records, 2788B target data | bytes_identical=true, aggregate_consistent=true; полный past multiset unchanged |
| 1 | 1459ms | ordinal 11 и 15; по 98B/5 records, 196B target data | Те же equality/retention checks |

В каждом 15 complete files: 10 quarantine → первый exact → 3 quarantine → второй exact.
Три промежуточных header-only файла наблюдаются в repeat_wait; раньше аналогичная по
порядку группа попадала в retention_wait. Это **не** доказывает, что её породил повторный
fetch, и не устанавливает источник потока. В каждом сеансе выбран один 7-byte target;
межсеансовые ID не сравнивались, offset не durable cursor. Совпадение first/second bytes
не является приёмкой долговременного архива или доказательством causal request-response
matching при queued stream. Свободный assembler не означает quiet transport.

HR encoded0 и candidate clock checks сохраняют ограничения 0.31: equality не подтверждает
семантику нулей, числовую точность или время. Clinical import/watermark не менялись.
**173 Java +48 Node PASS**, assembleDebug/lintDebug/editor PASS. Synthetic regressions:
same-ID/two-fetch/one-deadline, observed 15-file shape, prestarted quarantine, changed bytes
при одинаковых counts, changed counts, разные chunk boundaries, unsupported first/second,
CRC/pad/channel/plain/missing, общие byte/file bounds, timeout/cancel/EOF, retention
loss/duplicate/partial/flood, guards, private projection и digest wipe при observer failure.
Два test-only сценария observed shape/rechunking добавлены после hardware; runtime не менялся.
Оба socket closed/key absent/Mi Fitness process restored; финально оба keyfiles и production
lock отсутствуют. Mi Fitness BT reconnect не проверен. ACK8/5/SET/time/live/reset/unpair/
clear/private upload не было.

## mode=history_read: schema 20, только ограниченное чтение today

Точный порядок команд приложения: `1/26`, `1/27`, `2/1`, `2/2`, **`8/1`, до восьми `8/3`, `8/1`**. Последний index запрашивается только после полного получения и проверки всех выбранных файлов. Если разрешённых файлов нет — ни fetch, ни повторного index. Выбираются все уникальные ID `(type=0,subtype=0,detail=0,version=4)` и не более одной уникальной сводки `(0,0,1,5)`, суммарно не более восьми. Идентичные ID скачиваются однократно; полный исходный multiset сохраняется в памяти для retention comparison. Несколько разных сводок — `history_read_ambiguous_summary`, более восьми разрешённых уникальных файлов — `history_read_file_count_limit`; оба отказа до скачивания, без усечения/выбора наугад. Сводка запрашивается первой, затем details в порядке индекса. Неизвестные виды/версии пропускаются, никогда не скачиваются; это не полная выгрузка всех видов истории. Во время обычного history read дополнительный send-gate разрешает только 8/1 и 8/3. В `history_sample`/`history_sample_next`/`history_repeat` разрешён один read-only 8/2 для past metadata; 8/3 для past не отправляется. `history_sample_next` выбирает только второй уникальный details ID в порядке индекса, не выбирая summary. В repeat тот же первый ID запрашивается ещё раз, только после успешной первой проверки. Нет history ACK 8/5, SET 8/11, live/workout/time/user-profile и неявного fetch.

**Bulk wire facts:** SPPv2 Data type3, low nibble channel=5 (Activity), opcode=2, AES-CTR как для encrypted protobuf: receive key также IV, cipher перезапускается **для каждого сообщения/чанка**. Channel2 — другой unencrypted Data и не трактуется как история; при fetch неожиданное значение channel либо plaintext Activity прерывает сеанс. После дешифрования chunk начинается с `total:u16LE, number:u16LE` (4 bytes), number начинается с1; total постоянен, только строгий порядок без повторов/reset. Повтор последнего **SPP frame** допустим: только transport ACK, без повторного добавления application chunk. Sequence uint8 wrap обрабатывается. Дешифрованный file = `ID7 || zeroPadding1 || body || CRC32LE4`; CRC32/ISO-HDLC покрывает всё кроме последних4 bytes. Проверяются CRC, полное совпадение запрошенного ID и нулевой padding; никаких repair/fallback. CRC не криптографический MAC.

**Границы:** максимум 256 IDs/1792 bytes index, до8 уникальных файлов в полном bounded read; sample выбирает максимум2, repeat скачивает первый выбранный ещё раз, **512 KiB суммарных file bytes** (без 4-byte chunk prefixes), до2048 chunks на файл, до2880 detail records. Bulk SPP payload максимум64512 bytes только во время history_read; обычный cap4096 и protobuf cap4096 сохранены. Общий session frame cap8192, на file loop4096 сообщений; абсолютные deadlines описаны выше. ID/timestamp bytes, decrypted chunks и file buffer существуют только в памяти и best-effort обнуляются в finally/close (включая ошибку/cancel); ID не преобразуются в строки/даты. Собственные request builders с ID также очищаются. JVM/JCA внутренние копии полностью стереть гарантировать нельзя. Raw history никогда не записывается на диск/в Log; только безопасные projection maps.

### Все новые root-поля history_read

- `history_read_status`: `not_requested`, `target_mismatch`, `query_pending`, `query_failed`, `cancelled`, `no_allowed_daily_files`, `verified`, `verified_sample`, `parser_unsupported`, `repeat_file_changed`, `retention_index_changed`.
- `history_selected_file_count`: int после валидного index. `history_candidate_file_count` и `history_omitted_file_count` описывают bounded sample; `history_coverage` явно отличает sample от полного набора разрешённых файлов. `history_downloaded_bytes`: int только после integrity-check всех выбранных файлов.
- `history_file_download_attempted`: bool; true при переходе к фазе file. `history_file_ack_attempted:false`, `raw_history_persisted:false` всегда.
- `history_files`: array до8 безопасных объектов, включая частично полученный файл при ошибке. Категория/версия описывают **запрошенный** ID; при `file_id_match=false` они не доказывают вид полученных данных. Дополнительные nullable bool `file_id_timestamp_match`, `file_id_timezone_match`, `file_id_version_match`, `file_id_kind_match` сравнивают части ID только после проверки CRC, не раскрывают исходные значения. `request_receipt_seen_before_complete` отмечает полученный protobuf 8/3 до окончания файла; его отсутствие само по себе не ошибка. Никаких ID/дат/raw/числовых значений HR/GPS/kind/wear/аккаунтов.
- `retention_index_checked`: false до успешного повторного index; `retention_index_unchanged`: null до сравнения, затем bool. Сравнивается **полный multiset исходного today index** (порядок не важен), не только выбранные ID. false означает различие snapshots, **не обязательно удаление** (мог появиться новый файл); true **не доказательство глобальной сохранности/отсутствия потери истории**.
- `today_steps`: uint32/null, только из проверенного summary v5; `today_steps_status` = `not_available_until_verified_daily_summary_v5` либо `verified_daily_summary_v5`. Отсутствующий validity bit не становится0. Никакой клинической синхронизации.

Каждый `history_files[]` содержит общие поля: `type:0`, `subtype:0`, `detail:0|1`, `version:4|5`, `bytes:int`, `chunks:int`, `declared_chunks:int`, `transfer_complete:bool`, `crc_valid:bool|null`, `file_id_match:bool|null`, `received_id_in_original_index:bool|null`, `parser_status:string`. Для повторного чтения дополнительно `repeat_requested`, `repeat_read_count`, `repeat_aggregate_consistent`, `repeat_bytes_identical`; digest не сохраняется. null означает «проверка ещё не выполнена»; CRC ошибочного/неполного файла не выдаётся за успех. Дополнительные поля только после CRC/ID/padding:

| parser_status | Дополнительные безопасные поля |
|---|---|
| `daily_details_v4` | `detail_records`, `hr_present_records`, `hr_missing_records`, `step_records` — counts; `time_granularity_seconds:60`; `hr_presence_basis:documented_field_validity_only_not_measurement_validation` |
| `daily_summary_v5` | `summary_records:1`, `steps_present:bool`, `today_steps:uint32|null` |
| `not_parsed` или `unsupported_*` | Никаких выдуманных records/HR/steps; остаются только byte/chunk/integrity evidence |

**Details v4:** 6-byte nibble header; documented widths групп `2,1,1,2,1,1,2,1,1,2,2`, presence bit3, validity bits2..0. Шаги — третье поле группы0; дополнительный byte при second-valid и bit14 группы0 расположен после stress (группа8), до двух последних v4 групп. Считаются только records/field-presence: HR validity первой позиции группы4 не является медицинской проверкой числового HR. Пропущенные поля не превращаются в измерение0; сами значения HR не выводятся. Неизвестные kind/wear и timestamps не интерпретируются. Зарезервированный последний полубайт v4 игнорируется согласно upstream-парсеру; truncated record/extra byte, zero-width record, overflow → `unsupported_*`, без частичной догадки.

**Summary v5:** только точная длина **73 bytes** (`ID7+pad1+bitmap4+57 fixed-slot bytes+CRC4`), steps — первый u32LE по offset12, validity — bit7 byte8. Остальные31 фиксированных слота не проецируются. Иная длина → `unsupported_summary_length` при сохранённых CRC/ID evidence. Parser не пытается подогнать неизвестный формат.

`verified` даёт root `status:ok`; `verified_sample` и `repeat_file_changed` дают root `status:partial`; отсутствие разрешённых файлов/unsupported parser/изменение index → `partial` с соответствующим code; ошибки integrity/order/timeout → `error` и фиксированный безопасный code. Этапы `history_read_index_today`, `history_read_file`, `history_read_retention_index_today` пишутся перед запросами; на cancel — `cancelled`, socket close, никакого history ACK. Report не содержит дневника.

## Исправление обработки version response

Upstream `XiaomiSppPacketV1.getChannel()` определяет Version по channel=0, `XiaomiSppProtocolV1` передаёт payload в handler без opcode/dataType фильтра, `XiaomiSppSupport.handleVersionPacket()` читает первый байт. До инициализации шифрования payload остаётся неизменным. Старое ограничение opcode∈{0,2}, dataType∈{0,1} не было обосновано upstream: удалено **только для version channel**. Сохранены magic/length/epilogue/channel проверки, предел version payload 64, отказ на пустой/нулевой/неподдерживаемой версии без fallback или новых команд. Новые regression tests покрывают варианты opcode/dataType и отсутствие утечки payload остальных каналов. Аппаратный повтор показал channel=0, opcode=1, dataType=0, payload=2.0.97; прежний фильтр opcode отклонял такой ответ. В session config также удалено необоснованное требование точного совпадения версии peer с 1.0.0: upstream не использует её для выбора криптографии; проверки длины TLV и границ сохранены. После исправлений аппаратная авторизация прошла.

## Проверка

Проверка v0.16: **77 Java host tests + 14 Node tests PASS** (3 подготовки ключа, 7 runner, 4 Android safety); `assembleDebug` и `lintDebug` успешны. Полный текущий Android lint — `app/build/reports/lint-results-debug.html`. Старые `build/reports/verification.txt/json` описывают v0.6, не подтверждают новую сборку/аппаратный прогон. Production не менялся.

Аппаратный v0.13 bounded sample: один details-файл, 98 bytes, 5 records, HR/steps presence по 5/5, CRC/ID/padding PASS, retention index unchanged; 9 из 10 разрешённых кандидатов намеренно пропущены. v0.15 same-session repeat дважды получил тот же первый ID: оба файла 98 bytes, 5 records, `repeat_aggregate_consistent=true`, `repeat_bytes_identical=true`, retention unchanged; history ACK 8/5 не отправлялся. v0.16 `history_sample_next` получил следующий details-сегмент: 66 bytes, 3 records, HR/steps presence 3/3, CRC/ID/padding PASS, retention unchanged. Это PASS только для проверенных сегментов, не для всей истории и не для проверки временного выравнивания. Прежние v0.7–0.9 несовпадения ID остаются историей диагностики; строгая проверка ID не ослаблялась. При текущем индексе из 10 details обычный bounded `history_read` корректно отказался до скачивания из-за лимита 8; данные не усекались наугад.

Новые synthetic fixtures проверяют канонические 8/3 bytes, CRC32 vector/corruption/truncation, ID/padding, строгий chunk order/total, byte/aggregate budgets, unknown version/category, details4 extra byte/HR absence/record bounds, summary5 bitmap/unsigned steps/exact length, encrypted Activity channel5/CTR, SPP duplicate/sequence wrap/large payload bounds, retention comparison, memory wipe и строгий no-8/5/no-past/no-SET allowlist. Host safety tests дополнительно фиксируют screen flag lifecycle, absolute75s deadline, изолированный package/permissions и отсутствие raw persistence в codecs.

`VectorTests.java` содержит публичные/synthetic fixtures: version bytes, fragmentation/coalescing, malformed/length/CRC/proto bounds; RFC5869 HKDF case1; NIST SP800-38A AES-CTR F.5.1; RFC3610 CCM packet1; независимый Node/OpenSSL CCM nonce12/tag4 и synthetic Xiaomi-HKDF fixture; auth HMAC rejection; полностью scripted SPP v1 и v2 auth→battery→device info→live→finally stop. Scripted peer проверяет точный allowlist команд, но **не заменяет реальный браслет**.

## Источники фактов протокола (без включения их кода)

Снимок Codeberg master, проверенный 2026-11-12: commit **a047f5a1fe3067f3c685dca394b734bc11b79248**. Фиксированные ссылки позволяют воспроизвести исследование, хотя master меняется:

- [MiBand9Coordinator: BT_CLASSIC, обычная Band9](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/xiaomi/watches/MiBand9Coordinator.java).
- [XiaomiSppSupport: version request и переключение V1/V2](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppSupport.java).
- [XiaomiSppPacketV1](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppPacketV1.java), [XiaomiSppProtocolV1](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppProtocolV1.java).
- [XiaomiSppPacketV2: CRC/TLV/envelope](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppPacketV2.java), [XiaomiSppProtocolV2: ACK/session](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppProtocolV2.java).
- [XiaomiAuthService: 1/26, 1/27, miwear-auth HKDF, CCM, CTR](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiAuthService.java).
- [XiaomiUuids: standard SPP 0x1101](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiUuids.java).
- [XiaomiSystemService: 2/1,2/2](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiSystemService.java), [XiaomiHealthService: 8/45,46,47](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiHealthService.java).
- [Codeberg API schema discovery](https://codeberg.org/api/v1/repos/Freeyourgadget/Gadgetbridge/contents/GBProtobufGenerator/src/main/proto?ref=a047f5a1fe3067f3c685dca394b734bc11b79248); [xiaomi.proto](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/GBProtobufGenerator/src/main/proto/xiaomi.proto), [wear_account.proto API](https://codeberg.org/api/v1/repos/Freeyourgadget/Gadgetbridge/contents/GBProtobufGenerator/src/main/proto/xiaomi/wear_account.proto?ref=a047f5a1fe3067f3c685dca394b734bc11b79248), аналогично `wear_system.proto`, `wear_fitness.proto`.
- [HR/index schema API wear_fitness.proto](https://codeberg.org/api/v1/repos/Freeyourgadget/Gadgetbridge/contents/GBProtobufGenerator/src/main/proto/xiaomi/wear_fitness.proto?ref=a047f5a1fe3067f3c685dca394b734bc11b79248): HeartRate field 1 disabled, 2 interval, 3/4 high alarm, 5 advanced, 8 low alarm, 9/10/11 feature toggles. `XiaomiHealthService.handleHeartRateConfig` явно определяет interval в минутах и 0=smart; наличие полей в probe сохраняется, в отличие от proto defaults upstream.
- [XiaomiActivityFileId: descriptor category layout](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileId.java), [XiaomiActivityFileFetcher: separate conditional ACK after file data](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileFetcher.java). Только wire facts, не их реализация.
- [DailyDetailsParser: v4 group widths/extra byte/minute advance](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/DailyDetailsParser.java), [XiaomiComplexActivityParser: nibble presence/validity bits](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/XiaomiComplexActivityParser.java), [DailySummaryParser: v5 bitmap/fixed slots](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/DailySummaryParser.java). Probe не использует их persistence, device/database classes, raw dumping, CRC repair или ACK policy.
- [RFC5869](https://www.rfc-editor.org/rfc/rfc5869), [RFC3610](https://www.rfc-editor.org/rfc/rfc3610), NIST SP800-38A.

Не утверждается лицензия на Gadgetbridge или clean-room процесс с раздельными командами; это самостоятельный небольшой код interoperability, написанный после изучения публичных wire-фактов. GPL/AGPL реализация не vendored, не linked и не сгенерирована из их schema.
