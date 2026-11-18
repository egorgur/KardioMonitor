# Xiaomi Smart Band 9: контекст для полной реализации поддержки

Снимок диагностического состояния ниже: **2026-11-12, после аппаратного прогона probe 0.16**.
Новые результаты добавляются отдельными продолжениями; последнее — 2026-11-18.

## Продолжение 2026-11-18: sample-before-reference контроль

- Владелец явно разрешил до5мин RAM-only ожидания и временную паузу Mi Fitness.
  Добавлен internal `XiaomiHistoryReferenceSample`: только два fresh exact-ID/CRC/padding
  прошедших файла, byte equality; raw copy ≤512KiB, явное обнуление/close. Foreign,
  prestarted, bad CRC или изменённый repeat не создают пригодный образец. Обычный
  aggregate API не удерживает raw данные; sample callback — отдельный internal opt-in.
  Подтверждение retention и закрытие клиента обязательны до разрешения следующего этапа.
- Internal `captureHistoryReferenceSample(offset,sample,source)` допускает explicit today
  без reference только при repeat+sample, но не ослабляет обычный reference-first guard.
  Выбор — **явный offset0 текущего индекса**, не latest и не durable cursor. Прежние wire
  budgets/deadline30s/allowlist/exact guards сохранены. Clinical gates не открываются.
- Новый isolated `XiaomiReverseReferenceHardwareTest#testSampleBeforeReference` читает
  и удаляет private reverse-request, consumes/deletes auth-key, получает две передачи и
  retention, закрывает client. Только после этого атомарный schema1 `reverse-result.json`
  получает stage=sample_ready. Он не содержит ID/time/readings/digests/raw. Далее до300s
  ждёт private ASCII capture signal; Bluetooth больше не используется. Общий tooltip
  parser выделен в `XiaomiMiFitnessTooltip`, тот же независимый date/time+HR, две выборки,
  zone/DST guards; reference в reverse-режиме на диск вообще не записывается.
- [Host runner](../tools/band9-run-reverse-reference.mjs) — отдельная async library,
  не новый CLI режим старого runner. Сохраняет preconditions/owned production lock/
  stdin-only staging и exact default/enabled restore. Запускает один изолированный test
  async; после подтверждённого sample_ready восстанавливает Mi Fitness **до callback**,
  не останавливая RAM-владеющий test process. Сигнал посылает только оператор после
  подтверждения пользователя; runner его сам не создаёт. Startup90s/host post-ready330s
  ограничены; Android300s не продлевается. Выход/ошибка/таймаут→owned stop, независимый
  cleanup всех private файлов/lock и restore. Final PASS требует ещё memory-cleared,
  reference match и фактический instrumentation OK(1test), не только JSON.
- **Один аппаратный прогон:** sample_verified/repeat/retention=true; client_closed_before_
  reference/key_removed/request_removed=true до UI этапа; Mi Fitness restored до callback.
  Владелец синхронизировал и открыл последнюю сегодняшнюю точку. Capture=true, но
  record_located=false, timezone_matches=true, HR/steps=null; failure_stage=comparison.
  Sample memory явно очищена, signal/request/key/lock removed. Это не числовой HR mismatch.
  Before/after relation эталона с интервалом пока не экспортируется; не делать вывод о
  направлении смещения. Нельзя утверждать, что offset0 — последний/самый свежий файл.
- **Вывод:** reference-first окно не единственная причина блокировки; обратный порядок
  сам по себе не гарантирует перекрытие. Синхронизация/удаление как причинный механизм
  остаётся недоказанным. Следующий этап: отдельный явный latest-supported selector без
  перебора и safe before/inside/after relation образца с UI минутой; затем независимая
  time/HR/steps сверка. Не объявлять time mapping ошибочным или корректным по этому run.
- **113 protocol +19 architecture JVM,105 Node,5 synthetic instrumentation PASS**,
  protocol lint/isolated APK PASS. Unit cases проверяют actual held byte-array wipe,
  independent comparison after client close, quarantine/bad CRC/changed repeat; shared
  capture parser и private request/signal ошибки покрыты synthetic instrumentation.
  Key/reference/capture/reverse request/signal/tmp/lock absent, Mi Fitness enabled/process
  запущены, task-owned ADB helper остановлен. BT reconnect неизвестен. Обнуление явных
  raw массивов — best effort, не обещание очистки всех внутренних JVM/JCA/GC копий.
  Нет ACK8/5/SET/live/timewrite, host rawhistory persistence, clinical import или watermark.

## Продолжение 2026-11-18: граница доступного daily-окна

- Задача — разобраться с `selection/no_preceding_file`, не подгоняя время и не превращая
  отказ в разрешение fetch. Повторно проверены pinned upstream wire-факты:
  [XiaomiActivityFileId](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileId.java)
  декодирует LE epoch seconds в Date отдельно от signed quarter-hour zone;
  [DailyDetailsParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/DailyDetailsParser.java)
  начинает минуты с file timestamp и шагает60s. Текущая pre2038 гипотеза соответствует
  этому формату; она всё ещё требует независимой аппаратной time/value сверки.
- Новый internal `XiaomiHistorySelectionEvidence` сохраняет только enums/bool/null:
  reference-clock relation, supported aligned same-zone candidate-clock relation,
  coarse gap от reference до **самого раннего** начала (только если все позже reference),
  same-local-day этого начала. Phone clock снимается в instrumentation перед history;
  в обычном aggregate API нет чтения clock. Diagnostics содержит его приватно только
  на время сеанса, toString скрыт. Никаких dates/ID/HR/delta seconds в отчёте.
- Wire/schema3 поля: history_reference_clock_relation, history_candidate_clock_relation,
  history_after_reference_gap, history_after_reference_same_local_day. Host whitelist
  экспортирует их в history_selection_evidence только для schema3 reference report;
  invalid/missing→null, прочие схемы сохраняют прежний shape. Это наблюдение, не pass gate;
  reference selection/transfer budgets/allowlist/cleanup не изменены.
- **Один Samsung today auto-сеанс:** capture PASS; auth/exact identity/battery/settings GET
  PASS; `selection/no_preceding_file`. Evidence: reference `within_1h`, candidates
  `all_not_future`, earliest gap `under_5m`, earliest same local day=true. Таким образом,
  выбранная точка раньше всех eligible daily starts, но самое раннее начало лишь1–4мин
  позже; это не поддерживает гипотезу фиксированного сдвига на часовую зону или будущего
  индекса относительно phone clock. Ни одного8/3, reference comparison=null; repeat и
  retention не достигнуты. Не утверждать, что все файлы начинаются в пределах5мин.
- **Граница вывода:** отсутствие перекрытия этой точки с текущими supported daily
  descriptors установлено. Причина недоступности более раннего daily-сегмента, удаление
  после синхронизации/ACK Mi Fitness, clock skew в несколько минут и принадлежность
  manual HR другому типу не доказаны. Более ранние/другие категории не скачивались.
  Нельзя объявлять потерю истории, ошибку значений или time mapping PASS.
- **Следующий контролируемый эксперимент:** поменять порядок получения доказательств:
  bounded exact sample читать до предварительной синхронизации Mi Fitness, сохранить
  только в private RAM до завершения ограниченной проверки, затем вернуть Mi Fitness и
  независимо выбрать tooltip внутри интервала. Декодированный HR не использовать для
  выбора/создания эталона; raw bytes/dates не писать в host-файл. Нужен отдельный явный
  reverse-order harness, не автоматический retry/fallback из текущего runner. До его
  реализации текущие `history_reference*` гарантируют только reference-first порядок.
- **109 protocol +19 architecture JVM,87 Node,4 synthetic instrumentation PASS**, lint/
  isolated APK PASS. Тесты различают реальные past starts после reference и future clock,
  смешанные starts, local midnight без epoch adjustment, unavailable clock/empty и ноль
  fetch при отказе; host tests проверяют whitelists и legacy shape. Private key/reference/
  capture request/output/lock absent, Mi Fitness enabled/process запущены, task-owned ADB
  helper остановлен. BT reconnect неизвестен; clinical gates=false, no SET/live/8/5/import.

## Продолжение 2026-11-18: локализация reference selection

- Отказ предыдущего auto-сеанса локализован без вывода чувствительных данных:
  `history_diagnostics` содержит только фиксированные stage/selection. Стадии: index,
  selection, first_transfer, repeat_transfer, retention, boundary, complete. Причины
  selection различают empty/unsupported_epoch/unaligned_epoch/timezone_unavailable/
  no_preceding_file/outside_window/ambiguous/selected. Клиентские исключения по-прежнему
  нормализованы; диагностика живёт отдельно и не содержит исходного exception.
- Для независимого эталона добавлен isolated `XiaomiMiFitnessReferenceCaptureTest` и
  [host helper](../tools/band9-capture-reference.mjs). Он не навигирует и не обращается
  к Bluetooth/ключу: читает уже открытое окно Mi Fitness дважды с интервалом500ms,
  проверяет дату, однозначный tooltip time+HR и offset по zone rules; DST ambiguity
  отвергается. Нет waitForIdle (uiautomator не получал idle); null root ожидается
  ограниченно, до8 попыток по250ms. Формат UI привязан к текущему Mi Fitness/стенду.
  Expected day и четыре поля reference проходят только stdin/private memory; raw UI,
  даты и HR не сохраняются в host-файлах и не выводятся. После чтения private файлы
  удаляются и отсутствие проверяется; reference — non-enumerable поле результата helper.
- Reinstall APK мешал доступности уже открытого окна: после предварительной установки
  финального APK helper вызывается с явным `installApk:false` (default=true). Это отменяет
  только установку, не lock/preconditions/private staging/cleanup. Владелец повторно
  открывал tooltip после возврата Mi Fitness; успешный capture не является time/value PASS.
- **Hardware:** для предыдущего дня past auto отказал на selection/no_preceding_file;
  после нового замера и синхронизации сегодняшнего эталона past auto дал selection/empty.
  Причина отсутствия подходящих past descriptors не установлена; это не потеря данных.
- Добавлен отдельный **явный** `history_reference_today OFFSET/auto`: только today
  details v4, тот же exact transfer/repeat и полный multiset retention того же источника.
  Public past API/default не меняется; динамическая allowlist допускает только выбранный
  index subtype и8/3, cross-source retention не принимается, нет fallback/summary/8/5.
  Первоначальная реализация today остановилась на index: исправлен GET с health field5
  options(field1=0), оба ответа индексов используют health field2 для ID. Отдельный literal
  wire vector и scripted peer проверяют запрос/ответ, а не только общий fixture.
- **Повторный аппаратный today-сеанс после исправления:** capture PASS, auth/guard/
  battery/settings GET PASS; today index получен, `selection/no_preceding_file` до fetch.
  HR/steps/reference comparison=null, repeat/retention не достигнуты. Точные причины
  descriptor-time расхождения с UI ещё неизвестны; time-гипотеза не подгонялась. Нет
  автоматических retries/расширения лимитов, другой offset не перебирался.
- Финальные проверки: **106 protocol JVM +19 architecture JVM +84 Node PASS**, protocol
  lint/isolated APK PASS; **4 synthetic instrumentation PASS** на финальном APK.
  Architecture после source-bound изменений действительно перезапущена; её лимит40строк
  исправлен выделением selection helper без изменения поведения. Key/reference/capture
  request/output/lock отсутствуют, Mi Fitness enabled и process запущены; BT reconnect
  не доказан. Task-owned foreground ADB helper остановлен. Patient/staff APK, settings,
  live/ACK8/5, server, watermark не затронуты.
- **Следующий блокер B9-14/B9-P05:** независимая минута, действительно покрытая выбранным
  supported descriptor; затем exact-файл и сверка времени/HR, позже minutes/дни/steps/
  HR0/255/gaps/full coverage. Не принимать no_preceding_file за time mismatch или пустой
  архив, не переносить эталон и не ослаблять ID. Все clinical gates остаются false.

## Продолжение 2026-11-18: независимая reference-point проверка истории

- В production protocol добавлена **internal opt-in** сверка одной независимой минуты:
  UTC epoch + исторический offset + nullable HR/минутные steps. Публичный aggregate API
  по умолчанию по-прежнему не материализует записи. Обработчик вызывается только после
  свежего exact-ID/CRC/padding; foreign/prestarted/retention файлы не сверяются с эталоном.
- Epoch проверяется как minute-aligned, без повторного прибавления timezone; signed/u32
  расхождение после2038 не разрешается догадкой. Сравнение строго по индексу минуты,
  без поиска похожего пульса, допусков или переноса времени. Null validity не становится0;
  HR0/255 сохраняют неизвестную семантику и не принимаются как измеренный эталон.
- Runner `history_reference OFFSET` / `history_reference auto`: JSON через stdin,
  точные4поля/1024B, ранняя валидация, private staging/consume/delete и cleanup.
  Schema3 содержит только safe booleans/null, не dates/IDs/readings/raw JSON. При auto
  один кандидат — ближайший предшествующий supported descriptor из свежего past index,
  та же zone, не далее2880минут, ambiguity/absence→отказ. Нет fallback на другой файл,
  retry, увеличения wire budgets или persist offset. Выбор по time-гипотезе не timePASS.
- **Samsung/M2345B1/2.3.98:** независимо прочитан tooltip Mi Fitness с minute+HR;
  числовые значения и даты в отчёт/файлы host не сохранялись. Два ручных сеанса offset0
  (второй после добавления safe diagnostics) завершили оба exact чтения: bytes/aggregates
  identical, full past multiset unchanged. Reference не прошёл: `record_located=false`,
  `timezone_matches=true`, HR/steps comparison=null. Это **не HR mismatch**: точка вне
  выбранного файла. Steps эталона не было, daily total не подставлялся вместо минуты.
- После synthetic validation выполнен один отдельный auto-сеанс. Guard/battery/GET PASS,
  отказ в `history`, completed repeat/retention/reference comparison не получены. Причина
  history-phase пока не локализована: safe report не различает отсутствие подходящего
  кандидата и дальнейший transport/parser отказ. Автоматического обхода/повторов не было;
  не объявлять selection или time/value аппаратно прошедшими.
- **97 protocol JVM +19 architecture JVM +68 Node PASS**, protocol lint и isolated test
  APK PASS; **3 synthetic instrumentation PASS** на финальном APK. Они проверяют private
  parsing/deletion без Bluetooth/ключей и не заменяют независимую аппаратную сверку.
  Финальные auth-key/reference/lock отсутствуют, Mi Fitness enabled и process восстановлены;
  её Bluetooth reconnect не доказан. Временный host ADB helper завершается после проверки.
- **Блокер следующего шага:** безопасно локализовать auto history-phase, получить одну
  действительно покрытую exact-файлом независимую точку, затем несколько дней/минут,
  steps/HR0 и gaps/full coverage. `timeAndValuesVerified`, `fullCoverageVerified` и
  `clinicalImportAllowed` остаются false даже при успешном reference sample. Нет
  ACK8/5/SET/live/time write, patient/staff install, server upload или watermark.

Контракт эталона и ограничения: [Android testing](../android/docs/testing.md#независимый-эталон-истории-band-9).

## Продолжение 2026-11-18: wire decoder и воспроизводимый library repeat

- Самый сложный блок остаётся историей: независимая проверка времени/значений и полноты.
  Добавлен внутренний `XiaomiDailyDetailRecord` и `XiaomiDailyDetails.parseRecords` для
  wire-полей v4: позиция записи, nullable 14-bit steps, nullable unsigned HR. Отсутствующая
  validity не становится нулём, HR0/255 не получают выдуманную sentinel-семантику.
  Timestamp, wear/kind/intensity не вычисляются и в `ActivityMinute` не подставляются.
- Общий bounded parser обслуживает aggregate и record API. Обычный library verification
  не материализует списки числовых записей, возвращает прежние safe counts. Неверный size,
  чужая версия/kind/padding, truncation/extra и >2880 записей отвергаются; `toString`
  отдельной записи не раскрывает значения. Внешние exact-ID/CRC guards сохранены.
- На Samsung Mi Fitness перезапускалась после `force-stop`; временное отключение пакета
  по согласию владельца позволило пройти SPP. Наличие ACL само по себе не доказывает,
  кто держит RFCOMM. Добавлен явный `--pause-mi-fitness` только в production read-only runner:
  snapshot исходного default/enabled, проверка отключения, восстановление того же setting
  в finally и проверка процесса. Ошибка восстановления — cleanup FAIL; предшествующий
  disabled/unknown setting — отказ до установки/ключа. По умолчанию поведение не меняется.
- **Hardware, M2345B1/2.3.98:** probe0.32 `history_past_target 0 1` получил один exact
  details v4, 2674B/166 records, CRC/padding/full-past-retention PASS. Это sample, не
  166 доказанных измерений. Production `history_repeat 0` прошёл на прежнем APK, затем
  отдельно **на пересобранном APK с новым parser и штатным `--pause-mi-fitness`**:
  две exact передачи, byte/aggregate equality, retention unchanged, import blocked.
- Отдельный today `history_read` снова отказал **`history_file_id_mismatch`**: для summary
  пришёл CRC-valid82B details v4 с чужим ID. Он не разбирался; retention не достигнут.
  Полная history/today steps всё ещё BLOCKED. Наблюдавшийся ранее отказ Kotlin на стадии
  history в свежем repeat не воспроизвёлся; неизвестная причина не объявляется исправленной.
- Проверки: **82 protocol JVM +19 architecture JVM**, **54 Node**, protocol lint и сборка
  isolated instrumentation APK PASS. Устройство: временные ключи обоих APK и lock отсутствуют,
  исходный enabled-setting Mi Fitness восстановлен, процесс запущен. BT reconnect не доказан.
  Нет ACK8/5, SET/live, reset/unpair/clear, импорта/сдвига watermark или установки patient/staff.
- **Следующее доказательство:** независимые timestamp/timezone и HR/steps/HR0 reference,
  затем gaps/full-index coverage и возобновление по устойчивым идентификаторам, не offset.
  Нельзя включать clinical gates по успешному repeat или synthetic record tests.

## Продолжение: production foundation (2026-11-12)

Начата реальная интеграция обоих приложений, отдельно от диагностического APK:

- Явный protocol (`mi-band6-ble` / `xiaomi-band9-spp`), новый fail-closed QR v3 для новой
  выдачи обеих моделей; старый пациент не должен интерпретировать Band 9 как Band 6.
  Новый пациент сохраняет чтение v1/v2 и старых привязок без protocol как Band 6.
  Переход на новый формат согласован с пользователем; см. [QR-контракт](../android/docs/qr-contract.md).
- Production read-only core в `:band-protocol`, отдельные Xiaomi ACL в пациенте/персонале,
  один маршрутизируемый сеанс пациента, без fallback и без зависимости от tools/probe.
- Protocol metadata сохраняется вместе с инвентарём/привязкой, ключи остаются под Keystore.
  У пациента автоматическая запись legacy-интервала в Band 9 запрещена, GET разделяет
  nullable disabled/interval; 0 = Smart. Отсутствующий battery не становится нулём.
- **История, суточные шаги, live HR, SET и клинический Band 9 E2E не готовы.**
  Непроверенные операции блокируются явно, не отдают sample/нули и не двигают watermark.
  Wear-dependent напоминания и «нулевые» графики Band 9 не строятся из отсутствующих данных.
- Для аппаратной проверки нового core — отдельный library instrumentation APK и
  [безопасный runner](../tools/band9-run-production-readonly.mjs), не обновление приложений
  с данными пациентов. Аппаратный read-only тест нового core **прошёл** после исправления
  семантики `cancelDiscovery()` (false при остановленном поиске — не ошибка). Проверены
  guarded open в двух независимых сеансах, battery/settings (подтверждено наличие полей
  без публикации значений), закрытие сокета и удаление временного ключа; Mi Fitness
  возвращена как процесс, её Bluetooth-переподключение не доказано. Это не UI/фон/E2E.
  Итоги фиксируются отдельно в [чек-листе](band9-validation-checklist.md).
- Архитектурное решение и утверждённые новые ACL-границы:
  [ADR-0014](../android/docs/adr/0014-explicit-band-protocol-readonly-xiaomi.md).

## Продолжение: bounded-порции истории, probe 0.17

В диагностическом APK реализован `history_batch` (report schema 5): обязательные
`detail_offset` 0–255 / `detail_count` 1–2; только уникальные details текущего индекса,
без summary, не более двух файлов, без автоматического продолжения и без 8/5.
Offset — позиция в **свежем** индексе, не сохраняемый cursor. Retention сравнивает весь
multiset, но не доказывает одинаковый порядок/покрытие между разными сеансами.
Выход за доступные details отвергается до fetch; неполный хвост явно отмечается.
Прежние лимиты и проверки CRC/ID/padding не ослаблены. Production history/live/SET
по-прежнему заблокированы; новые порции не импортируются и не двигают watermark.

Host-проверка: 84 Java protocol tests и 5 Activity safety tests PASS, assemble/lint
успешны. На телефоне отдельно проверены четыре ошибочных Android Intent (нет range,
неверный тип, отрицательный offset, нерелевантные extras): schema 5,
`invalid_history_range`, `hardware_attempted:false`, временного ключа нет.
Аппаратные результаты: offset=2 отсутствует и отклонён до fetch. Отдельный index GET
теперь показал today=1 details v4 + 1 summary v5, past=10 details v4; прежние позиции
не долговечны. Явный batch 0/2 выбрал один details, но полученный CRC-valid файл
не совпал по timestamp ID и отсутствует в исходном today index; parser не запускался,
retention не достигнут. **BLOCKED: причина mismatch и bounded past-read**, не повод
ослабить ID/временные проверки. ACK/SET/live не отправлены, ключ удалён, Mi Fitness
возвращена как процесс, Bluetooth reconnect не доказан. Подробности и 26 Node safety
тестов — в [чек-листе](band9-validation-checklist.md).

## Продолжение 2026-11-13: mismatch provenance и bounded past-read, probe 0.20

- На этапе v0.20: probe **0.20**, versionCode20, report schema8; тогда runner принимал
  только их. Добавлен `history_past_batch OFFSET COUNT`: 1–2 уникальных details из
  свежего past index, metadata today, exact ID/CRC/padding и past retention после
  успеха. Прежние today-режимы сохранены, нет автообхода/8/5/SET/live/raw persistence.
- Снимки today/past остаются в памяти до завершения сеанса и очищаются в finally.
  Сравнение received ID с обоими снимками возвращает только bool/null и counts;
  попадание в другой индекс НЕ позволяет принять не тот файл. Missing index — отказ.
- v0.18 today 0/1: candidates5 (4 details+summary), past10; CRC-valid98B файл не
  совпал ни с today, ни с past snapshot. v0.19 past0/1: candidates10details,
  CRC-valid82B файл также отсутствует в обоих индексах. Все отказы до parser/retention.
- v0.20 past0/1: CRC-valid98B, timestamp ID не совпал, timezone/version/kind совпали,
  membership обоих индексов=false. Enum `file_id_timestamp_relation:unclassified`:
  byte reversal / ±signed descriptor timezone×900 / same minute не объяснили mismatch.
  Это только арифметическая диагностика, не декодированные измерения/время.
- Проверены **93 Java protocol +28 Node safety tests**, assembleDebug/lintDebug PASS.
  Временные ключи удалены, socket закрыт, Mi Fitness process возвращён; reconnect
  по Bluetooth не доказан. Клинические приложения, их данные и сервер не трогались.
- **BLOCKED остаётся источник другого файла**, не доступность API past. Дальше нужен
  разбор очереди/корреляции 8/3 и bulk (возможный незапрошенный/задержанный ответ пока
  только гипотеза). Не посылать 8/5 для «разблокировки», не принимать received ID
  вместо requested и не интерпретировать истории без подтверждения времени/значений.
  Нет оснований включать production history/live/today steps.

## Продолжение 2026-11-13: unsolicited bulk подтверждён, probe 0.21–0.22

- Новый `history_correlate OFFSET 1`: один past target и один8/3, максимум два
  CRC/padding-checked заголовка до первого exact ID. Никакого parser/import,
  retention/retry/дополнительного fetch/8/5; diagnostic partial даже при exact ID.
- Аппаратный **v0.21/schema9**: до8/3 при ожидании индексов уже10 bulk-кадров.
  Первый файл34B не совпадает и отсутствует в обоих снимках; transport ACK fetch
  ещё не замечен. Второй1394B точно совпадает с requested ID/past snapshot;
  transport ACK уже замечен. Protobuf receipt не замечен. Результат
  `diagnostic_exact_after_unmatched`, оба файла не разобраны, retention не проверен.
  Посторонний поток в сеансе подтверждён; его источник/причина ещё неизвестны.
- На этапе **v0.22/versionCode22/schema10** добавлены safety после этого наблюдения:
  максимум32 пропущенных encrypted channel5 кадров во время ожидания protobuf и
  общий512KiB для пропущенных+обрабатываемых payload включая chunk headers.
  Один30s correlation-stage, абсолютный75s, без retry при timeout/cancel/flood.
  Header-only метод package-private; обычный parser по-прежнему требует exact ID.
  Runner отвергает v0.21/schema9, не смешивая старую hardware-сборку с новой.
- **99 Java protocol +30 Node safety tests PASS**, assemble/lint PASS. Проверены
  суммарный byte budget до/после fetch, plaintext/flood, CRC/pad/channel, EOF,
  timeout/cancel, один fetch, не более двух заголовков, safe projection/cleanup.
  v0.22 caps пока проверены synthetic peer, аппаратный correlation — v0.21.
- Аппаратный socket закрыт, ключ удалён, процесс Mi Fitness восстановлен; её
  Bluetooth reconnect не доказан. Нет SET/live/reset/unpair/raw persistence;
  production-приложения и данные пациентов не трогались.

**Далее:** bounded demux/quarantine с обязательным exact target ID и общими
бюджетами, затем target-only read/retention и независимая проверка времени/значений/
покрытия. Нельзя считать «первый complete bulk = ответ», принимать received ID вместо
requested или посылать8/5 для очистки. **Полная production история, today steps,
live stop, SET restore и стендовый фиктивный пациент E2E по-прежнему не готовы.**

## Продолжение 2026-11-13: bounded target-only demux, probe 0.23

- На этапе v0.23 версии **0.23/versionCode23/schema11**, тогда runner принимал только их.
  Новый явный `history_target OFFSET 1` выбирает ровно один past details target:
  `8/2 → 8/1 metadata → один8/3 → 8/2 retention`. Прежние batch/read/correlate не
  переходят на demux неявно. Production core/приложения на этом этапе не менялись.
- Новый package-private `HistoryDemultiplexer` собирает и quarantines чужие файлы
  с первого index wait до retention; без expected ID и без parser. Буферы/ID wiped.
  Unknown category — только CRC/pad/header-only. Ранее начатый файл остаётся quarantine
  даже при продолжении после запроса/совпадении будущего ID. Chunk reset/interleaving,
  corrupt/plaintext/channel abort; никакого «пропуска ошибок до хорошего файла».
- После одного8/3 не более двух completed файлов до exact target (включая завершение
  prefetch partial). Только newly-started exact-ID/CRC/pad target проходит aggregate
  parser; post-target bulk при retention снова quarantine. Проверка полного past
  multiset, abort при incomplete trailing quarantine, unsupported/changed index не
  выдаются как verified. `verified_target_sample` всегда upper-level partial.
- Общие512KiB для всего channel5 payload с chunk headers,32 protobuf-wait bulkframes,
  16 complete files,один30s target-stage/absolute75s; duplicates не counted повторно.
  В отчёте только routes/phase/ordinal и прежние safe counts/bools; нет raw/чисел
  измерений/времени/клинического импорта/8/5/retry/auto-cursor.
- **113 Java protocol +32 Node safety tests PASS**, assembleDebug/lintDebug PASS.
  Покрыты partial до/после команды, early exact quarantine, mismatch→exact/two-mismatch,
  EOF/timeout/cancel, общие budgets, duplicate/order/plaintext/CRC/pad/channel,
  retention bulk/partial/corrupt/change, unsupported layout, wipe/observer failure.
- Начальный hardware preflight screen lock остановлен до key/BT. После разблокировки
  пользователем и согласования одного прогона установлен только isolated probe0.23
  (без clear). `history_target 0 1`: auth/exact identity PASS, selected1 из10past.
  В index wait10 полных CRC/pad-valid файлов868B quarantined; после одного8/3 ещё418B
  и82B, оба ID mismatch/нет в today/past snapshots. Guard `history_target_not_received`
  за928ms, все12 parser=`not_parsed`, retention не достигнут, retry не было.
  **Target read аппаратно не прошёл**; fail-closed подтверждён. Третий реальный файл
  неизвестен, нельзя считать что exact target обязательно придёт следующим.
- Добавлен synthetic regression этой формы с полностью фиктивными байтами/ID:
  10prefetch+2mismatch, queued третий target не читается, нет retention/8/5.
  Финальные probe+production keys/lock отсутствуют, socket закрыт, MiFitness process
  восстановлен; Bluetooth reconnect не доказан. Нет SET/live/reset/unpair/raw dumps.
- **Следующий барьер:** понять правила маршрутизации/границы unsolicited потока
  до новых hardware fetch, без увеличения окна/повторов наугад и без8/5. Текущий
  target-mode оставить fail-closed; не объявлять его аппаратно прошедшим.

После hardware target/retention отдельно подтвердить time/value semantics, summary/today
steps, устойчивое покрытие истории. Полная production history/live/SET и E2E на фиктивном
пациенте/отдельном стенде остаются не готовы; sample нельзя выдавать за полную историю.

## Продолжение 2026-11-13: unsolicited без fetch, probe 0.24

- На этапе0.24: **0.24/versionCode24/schema12**, runner отвергал прежние версии. Новый
  `history_boundary` без range выполняет только **8/2 → 8/1** после auth/exact identity;
  отдельный gate не допускает8/3/8/5/SET/live. Это probe-only диагностика, не production.
- `HistoryBoundary` принимает только bounded encrypted channel5/strict chunkorder/
  CRC/pad, без parser; фиксирует index subtype и ACK-seen при первом/последнем bulk.
  До16 проверенных7byte ID остаются приватно для deferred membership после получения
  today/past snapshots; payload сразу wiped, ID/snapshots wiped в finally (включая
  observer failure). Missing/invalid snapshot или partial ID — membership=null.
  Только bool/count/category/enum metadata выводятся, никаких raw/HR/steps/time.
- Лимиты512KiB с chunk headers/32bulkframes/16files/6s index-stage/absolute75s сохранены.
  Завершается на втором index response, не ждёт бесконечной тишины/не делает flush.
  Partial хвост остаётся diagnostic incomplete, не доказательством quiet stream.
  Target-window2 и предыдущий fail-closed target-read не ослаблены.
- **Hardware один index-only прогон**:1088ms,diagnostic partial,fetch_command_count=0.
  Auth/exact model/firmware PASS, оба index responses после своих transportACK.
  Во время today-stage10 completefiles/10bulkframes,868B data/908B с chunk headers;
  все CRC/pad valid, все received IDs вне обоих снимков, все not_parsed.
  7files622B до today transportACK,3files246B после. На момент финального response
  partial хвоста нет; последующие файлы не наблюдались и не объявляются отсутствующими.
- Подтверждён поток **без8/3**, но не источник/причина. ACK-boundary недостаточен:
  post-ACK файлы также чужие. Нельзя просто начать приём после ACK/поднять окно/повторить
  target fetch. Следующий контроль — различить bounded пассивное окно после identity
  до index и post-index traffic, без новых write-команд и без выводов о причинности
  только из порядка чтения. На этапе0.24 такого контрольного hardware сеанса ещё не было;
  результат следующего пассивного0.25 сеанса описан ниже.
- Pinned public facts: `XiaomiHealthService`1015–1096 строит те же8/1,8/2,8/3 и после
  index сам запускает client-side fetch queue; это не доказательство wire behavior
  браслета без8/3. В изученном4byte chunk header `XiaomiActivityFileFetcher`93 только
  total/number, не request token. Ссылки в [чек-листе](band9-validation-checklist.md).
  Ни upstream reset/dumps/ACK/слабая верификация, ни исходники не переносились.
- **121 Java +34 Node tests PASS**, assemble/lint PASS: ACK ordering, deferred membership,
  cross-index partial, nofetch/noParser, malformed/flood/budgets/timeout/cancel/wipe.
  Synthetic regression10files/7preACK+3postACK — только новая фиктивная форма, не история
  браслета. После hardware добавлен host guard запрета чтения wiped ID после close;
  аппаратный сеанс ради него не повторяли.
- Socket закрыт, финальные keys/lock absent PASS, MiFitness process восстановлен;
  Bluetooth reconnect не доказан. Нет8/3/8/5/SET/live/raw persistence/клинических данных.

Production history/today steps/live/SET остаются выключены. Full time/value/coverage,
ретенция target на hardware, фон/CDM и фиктивный пациент на отдельном стенде не готовы.

## Продолжение 2026-11-13: пассивный pre-index контроль, probe0.25

- На этапе0.25: **0.25/versionCode25/schema13**, runner принимал только эту пару. Новый
  `history_passive` без extras: auth+battery+exact identity/SPPv2 → одно6s окно **без
  любых history/index/fetch/SET/live/time команд**. Отдельный send-gate запрещает все
  application sends в окне; incoming SPP transport ACK не являются8/5.
- `historyPassive` использует monotonic clock и один deadline, переданный Activity;
  capped absolute75s и не продлевается traffic. Только SocketTimeout на/после deadline
  завершает окно; ранний timeout/EOF/cancel/malformed/budget — abort. Cutoff может быть
  посередине SPP header/payload: transport boundary=`not_verified`, отсутствие потока
  всегда не доказано. `incomplete_file_present` — лишь наличие начатого assembler,
  не достоверный признак partial transport frame. Нулевые counts не HR/steps значения.
- Reuse header-only `HistoryBoundary` с retainIds=false: private verified ID copies
  не создаются, payload wiped, no parser. Subtype/ACK/membership=null, file phase
  `pre_index_passive`; нет индексов и сравнение не подменяетсяfalse. Лимиты прежние
  512KiB channel5/32bulkframes/16files/32protobuf waits; wipe/observer failure и gate
  проверены synthetic fixtures. Target-window2 и production-код не менялись.
- **Hardware ровно один `history_passive`:** HMAC/auth/exact identity PASS,6000ms окно
  истекло,6909ms сеанс; root partial / `diagnostic_passive_window_elapsed`.
  Наблюдалось0 complete bulkframes/0bytes/0completefiles, нет начатого file assembler;
  history commands/index responses/fetch0, download/ACK/retention=false. Это не
  доказательство отсутствия любого transport хвоста/будущего потока или того, что
  index вызывает наблюдавшиеся в0.24 unsolicited файлы. В этом сеансе index не было.
- **128 Java +36 Node tests PASS**, assemble/lint PASS. Silence, unsolicited/unknown/
  duplicates/control, partial file/header/payload на deadline, early timeout/EOF/cancel,
  malformed/flood/bytes/frame bounds, guards/no sends/no ID copies/wipe/observer failure.
  Editor diagnostics чисты. Installed только isolated probe, socketclosed, оба keys и
  production lock absent; MiFitness process возвращён, BT reconnect не доказан.
- **Следующее исследование:** один изолированный index subtype/post-index bounded
  window безfetch, чтобы отделить scopes/stages; сначала host safety/guards, затем
  отдельный явно описанный безопасный аппаратный контроль. Не повторять target fetch,
  не расширять окна и не ACK8/5. Один порядок чтения всё равно не доказывает причину.

Полная production history/today/live/SET, time/value/coverage, target retention на
железе, фон/выдача и E2E фиктивного пациента на отдельном стенде остаются BLOCKED.

## Продолжение 2026-11-13: past-only post-response окно, probe0.26

- На этапе0.26: **0.26/versionCode26/schema14**. `history_past_window` без range выполняет
  auth/battery/exact identity/SPPv2 → **один8/2** → header-only окно до/после ответа.
  Одноразовый send-gate разрешает лишь этот send и запрещает повтор/8/1/8/3/8/5/SET/live.
  Incoming transport ACK сохранён, он не historyACK. Production-код не менялся.
- Один monotonic deadline6000ms начинается до8/2 и передаётся Activity под absolute75s;
  ответ/traffic его не продлевают. Не drain-until-idle. Только timeout на/после deadline
  с валидным единственным past response даёт diagnostic elapsed partial; missing/
  absent-field/invalid/wrong/duplicate response, early timeout/EOF/cancel/malformed/
  budgets → ошибка. Пустой валидный snapshot не считается отсутствующим.
- Header-only quarantine512KiB/32bulkframes/16files и wipe сохранены. Phase first/last
  bulk=`index_wait`/`post_index_response`, counters до/после response; last metadata
  фиксируется на реально принятом chunk, не вычисляется из stage при close. Private
  verified ID/snapshot только для past membership и wiped; today membership=null.
  Нет parser/raw persistence/значений/retention. Cutoff SPP boundary=`not_verified`,
  absence=false — отсутствие потока не доказано даже без partial assembler.
- **Один hardware0.26:** auth/HMAC/exact identity PASS,6000ms окно/6953ms сеанс,
  root partial / `diagnostic_past_window_elapsed`. Один8/2, один response послеACK,
  fetch0; **10completefiles/10bulkframes,868B filedata/908B сchunkheaders**. Все10 после
  past response иACK, доresponse0frames; все CRC/pad valid, past membership=false,
  today=null, parser=not_parsed. Partial assembler нет, download/ACK/retention=false.
  Следовательно, neither today8/1 nor fetch8/3 необходимы в этом наблюдении; отбросить
  pre-response traffic недостаточно. Не доказаны причинность8/2/источник/время/одинаковые
  файлы между сеансами/отсутствие позднего потока. Counts/bytes не доказывают equality.
- **135 Java +38 Node tests PASS**, assemble/lint PASS, editor errors нет. Synthetic
  before/after/spanning иACK ordering, duplicate transport/index, empty/absent/missing/
  invalid/wrong index, no extra sends, observer failure/wipe, partialfile/SPP header/
  payload, early timeout/EOF/cancel, malformed/flood/bounds/identity. Hardware-shape
  regression10post-responsefiles/908B/outside snapshot использует фиктивные ID/байты;
  добавлен после hardware, сеанс не повторяли. Обновлён только isolated probe;
  socketclosed, обаkeys/productionlock absent, MiFitness process restored, BT reconnect
  не доказан. Клинические приложения, данные пациентов и сервер не трогались.
- **Дальше:** подтвердить source/routing/семантику ID независимыми wire-фактами, не
  считать post-response или post-ACK файл target. Не повышать окно/повторять fetch
  наугад/ACK8/5. Exact-target иretention наhardware, time/value/coverage, todaysteps,
  live-stop/SETrestore, фон/выдача/Keystore иE2Eфиктивного пациента ещё не приняты.

## Продолжение 2026-11-13: wire ID contract/lifetime, probe0.27 host-only

- На этапе0.27: **0.27/versionCode27/schema15**, runner принимал только новую пару.
  **APK не устанавливался, новых аппаратных запросов не было**; последнее наблюдение
  hardware —0.26 выше. Новый mode/send не добавлен, production-код/gates не менялись.
- Публичный pinned a047f5a1fe3067f3c685dca394b734bc11b79248 независимо подтвердил:
  Command.health10, Health.activityRequestFileIds bytes2 (не repeated protobufmessage);
  index — конкатенация7byte ID без count/record-prefix; ID4timestampbytesLE+timezone+
  version+flags; chunkprefix4total/number, assembledfile IDoffset0/pad7/CRC32LEtail4.
  Прямые ссылки/schema path `GBProtobufGenerator/src/main/proto/xiaomi/wear_fitness.proto`
  в [чек-листе](band9-validation-checklist.md) и [probe README](../tools/band9-probe/README.md).
  Прежний путьapp/src/main/proto был неверен; файлы найдены через public tree metadata.
  Комментарии schema о command numbers местами расходятся с executable constants/
  handler/send, поэтому проверены последние. Клиническая time/value semantics не доказана.
- Оснований менять header/index offset, endian или ослаблять exact7bytes **нет**.
  Literal synthetic protobuf с2IDs, high timestamp/timezone bits, exact8/3bytes и
  mutation каждого byte проверены; raw байты не нормализуются и не превращаются в даты.
  Источник hardware mismatch пока неизвестен, public source не заменяет hardware.
- Найден/воспроизведён отдельный lifetime bug: послеclose file мог сравниваться со
  wiped нулевым ID/давать digest wiped buffer, snapshots — ложную retention equality.
  Regression сначала упал на старом поведении. Теперь `HistoryFile.requireOpen` запрещает
  accept/verify/membership/digest/summary, HistorySelection — ID/count/membership/
  retention для обеих сторон, даже пустых. Closed-object ошибки IllegalStateException,
  не fakefalse/0/true. Закрытие идемпотентно и wipe сохранён; quarantinedIdCopy guard
  по-прежнему требуетopen+CRC+pad. Ранее созданные safe maps detached отrawbuffers.
  **Не показано**, что defect участвовал в аппаратных наблюдениях; это не решениеmismatch.
- **139 Java +38 Node tests PASS**, assemble/lint PASS. Literal ID contract/mutation,
  snapshot ownership послеProto.close, file/index closedguards, lifetime до/послеwipe,
  пустой snapshot, idempotentclose плюс прежние malformed/flood/wipe/no8/5regressions.
  Реальные ID/keys/history вfixtures не использовались; upstream-код не копировался.
- Дальше требуется независимо подтвердить source/routing и соответствие indexed ID
  входящим файлам. После0.26 нельзя считать ни postACK, ни postresponse файл target.
  Не повторять fetch/расширятьwindow/ACK8/5 наугад. Production history/today/live/SET,
  fulltime/value/coverage/targetretention иfictional-patientE2E остаются BLOCKED.

## Продолжение 2026-11-13: варианты mismatch и past-only exact target, probe0.28–0.29

- На этапе0.29: **0.29/versionCode29/schema17**, установлен только isolated probe, runner
  отвергает прежние версии/схемы. Production-код и функциональные gates не менялись.
-0.28 добавил `history_today_window` (один today8/1/6s, безpast/fetch),
  `history_target_scan OFFSET 1` (past→today→одинfetch, до6post-request files)
  и private nearest-ID diagnostics. Полный бюджет остаётся16files/512KiB, включая
  prefetch/chunk headers; max32bulk при ожидании protobuf, один30s file stage/75s session.
  Только headers/CRC/pad, никаких parser/import/retention/ACK8/5/SET/live.
- Diagnostic fields: `today_`/`past_minimum_differing_id_bytes`,
  `*_nearest_id_difference_mask` (bit0..6 позиции в7byteID),
  `*_nearest_id_mask_ambiguous`. Пустой snapshot→null, равные минимумы с разными
  masks→masknull/ambiguoustrue. Не выбирает ID, не нормализует timestamp/timezone.
  Boundary дополнительно сравнивает private verified IDs внутри окна для
  `received_id_repeated_in_window`, затем всё стирает. Нет межсеансового сравнения.
- **Hardware0.28, четыре отдельных сеанса:**
  1. Today-only:6912ms/6s;7files,973Bbulk/945Bfiledata, всеpost-response/ACK ивнеtoday.
  2. Scan offset0:881ms;10pre-request+6post-request,1763Bfiledata;6mismatch, failclosed.
  3. Scan offset1:1169ms;10pre-request+6post-request,1456Bfiledata;6mismatch, failclosed.
  4. Past-only контроль:7047ms/6s;10files,908Bbulk/868Bfiledata, всеpost-response/ACK,
     всевнеpast, ближайшие masks2/3 — различия толькоtimestampbytes. ПовторовID в двух
     no-fetch окнах не было. Counts/bytes не подтверждают одинаковые файлы между сеансами.
- На основании этих результатов0.29 добавлен **`history_past_target_scan OFFSET 1`**:
  ровноpast8/2→одинpast8/3, без промежуточного today8/1, send-gate запрещаетtoday.
  До16completed после запроса, но те же16files/512KiB глобально (не добавочный бюджет).
  Prestarted/quarantined не становится target, exact/CRC/pad обязательны; parser и
  retention намеренноне вызываются. Приexact статус`diagnostic_target_scan_exact`, partial.
- **Hardware0.29, два независимых offsets0/1:** в обоих10mismatch, затем **11-й
  fresh-after-request файл exact ID**, CRC/padding valid, membershippast=true,
  membershiptoday=null, expected-ID headeroffset0. Offset0:852ms,target1394B,
  totalfiledata2262B. Offset1:937ms,target98B,total966B. В обоих ровноодинfetch,
  `not_parsed`, retentionfalse, ACKfalse/rawpersistfalse. Auth/HMAC/exactidentity/SPPv2 PASS.
- **Узкий вывод:** найден диагностический порядок получения exact target в двух
  независимых сеансах с offsets0/1 без ослабления identity. Межсеансовое различие ID
  не проверялось: offset не является durable cursor. Нельзя считать это доказательством
  source/причины unsolicited потока, полноймаршрутизации или безопасногоимпорта.
  Исключениеtoday и увеличениеpost-requestlookahead проверены вместе, не по отдельности.
  Не переносить6/16 lookahead как production-эвристику и не принимать mismatch.
- **148 Java +42 Node PASS**, assemble/lint/editor PASS до нового hardware; исправлено
  только неверное synthetic ожидание числа pre-exact файлов. Добавлены tests7byte masks/
  ambiguity/empty/closed/wipe, today-onlymalformed/bounds,6file scan, past-onlyexactпосле10,
  no-today/no-parser/no-retention/bytebudget16files/malformed иversion/projection/cleanup.
- После всех6сеансов temporarykey absent/socketclosed/MiFitnessprocessrestored;
  финально оба temporarykeyfiles иproduction lock отсутствуют. MiFitnessBTreconnect
  не подтверждён. Никакихreset/unpair/clear/SET/live/historyACK, private upload/логирования.
- **Следующий этап:** отдельный bounded past-only aggregate/retention контроль с той же
  строгойдемультиплексацией; затемtime/value/coverage. Не считатьheader-onlyexact чтением
  полнойистории, не двигатьwatermark. Productionhistory/today/live/SET/E2E остаются BLOCKED.

## Продолжение 2026-11-13: past-only aggregate HR/steps и retention, probe0.30

- На этапе0.30: **0.30/versionCode30/schema18**, установлентолькоisolatedprobe. Новыйmode
  **`history_past_target OFFSET 1`** черезsafe runner;rangeобязателен,COUNTтолько1.
  Productionкод/приложения/gates не менялись. ACK8/5/SET/live/time/reset/unpair запрещены.
- Session.historyPastTarget использует тотжестрогийdemux past-only, ноheaderScan=false:
  **8/2→один8/3→8/2**, никакогоtoday8/1. Prestarted/mismatch/retention filesheader-only;
  exactfreshID+CRC+pad→DailyFileSummaryaggregate. Retentionсравниваетполныйpastmultiset,
  включаядубли/unknowncategories,непорядок. Нетповторовfetch,clinicalimport/cursor/watermark.
- DemuxpastOnly позволяетдо16completedпослезапроса,но прежниеглобальные16files/512KiB
  (включаяprefetch/chunkheaders/retention) несбрасываются. 32bulkприprotobufwait,
  file30s/index6s/session75s. InvalidCRC/pad/channel/plain/order/bounds/partialretention
  не даютverifiedsample. Unsupportedlayout→parser_unsupportedбезвыдуманныхнулей.
- Activity/runnerпубликуют `history_time_values_verified=false` и
  `history_full_coverage_verified=false`; `verified_target_sample` остаётсяpartial.
  HR/stepaggregates — countвалидныхполей,неперсональныезначенияинеverifiedminutes.
- **Hardware0.30, дваfreshсеанса offsets0/1:**
  -0:1403ms,exactordinal11/1394B,86records/86HRpresent/0HRmissing/86steprecords.
  -1:1410ms,exactordinal11/98B,5records/5HRpresent/0HRmissing/5steprecords.
  Вобоих fullpastretentionchecked/unchanged=true,10detailcandidates/selected1/omitted9,
  exactmembershippast=true/today=null. МежсеансовыеIDнесравнивались.
  По10mismatchпередtarget+3header-onlyretention=14files,4703B/3407Bfiledata,
  всеCRC/padvalid,parsedровноordinal11. Auth/HMAC/exactidentity/SPPv2PASS.
- **157 Java +44 Node PASS**,assemble/lint/editorPASS. Runtimeпроверенпередhardware;
  посленегодобавленыtest-onlysynthetic14files/ordinal11/3retentionиbudget/cancelregressions,
  suitesповтореныбезновогоhardware. Такжеcoveredretentionchanged/duplicate/absent/invalid/
  partial/CRC/pad/flood,unsupported/nozero,prestartedquarantine,guards,version/projection/cleanup.
- Socketclosed,keyabsent,MiFitnessprocessrestoredвобоихсеансах; финальнообаkeyfilesи
  productionlockотсутствуют. MiFitnessBTreconnectнеподтверждён. Нетrawpersistence,
  privateupload/ID/HRvalueslogging,clinicdatachanges.0.30install-rбезclear.
- **Остаётся:** независимопроверитьabsolute time/timezone/HRиstepvalue semantics,
  частоту/пропуски,полнотуcoverageиповторнуювыгрузкуархива; затемproductionadapter/фон/E2E.
  Retentionдвухsnapshotsнедоказываетquietstreamилисохранностьвсегоархива.
  Необъявлятьданные86/5recordsполнойисторией; productionhistory/today/live/SETBLOCKED.

## Продолжение 2026-11-13: time/value encoding diagnostics, probe0.31

- На этапе0.31: **0.31/versionCode31/schema19**, установлен толькоisolatedprobe install-r.
  Production/gates не менялись. Wireпорядок `history_past_target OFFSET 1` тотже
  past8/2→один8/3→past8/2;16files/512KiB и stage/session deadlines сохранены.
- Public pinned GBcommit a047f5a1fe3067f3c685dca394b734bc11b79248: DailyDetailsParser,
  XiaomiComplexActivityParser, XiaomiActivityFileId. Ссылки/контрактполей —
  [probe README](../tools/band9-probe/README.md#диагностика-времени-и-кодирования-значений-v031schema19).
  Steps — nibblebit0 validity + младшие14бит word; extra — nibblebit1 + wordbit14.
  HR — nibblebit2 validity + unsignedbyte. Никакой подтверждённой HR0/255 sentinel
  семантики upstream не даёт; stress255 exception нельзя переносить на HR.
- DailyFileSummary считаеттолькокатегории encoded HR0/255 и stepzero/nonzero приvalidbit.
  Поляnamed `*_encoded_*_records`, value_interpretation_status=source_encoding_only_not_measurement_validation.
  Unsupported/truncated layout не экспортирует новыеcounts/timeполя. Не клинические значения.
- Candidate timeline: u32LE descriptor epoch +60s/record без второгоzoneoffset; upstream
  используетsignedint, поэтому отдельный descriptor_signed_unsigned_epoch_agree. u32
  не объявлен доказанным форматом дат после2038. Толькоminutealignment/civilzonebool,
  ageband и last-not-future относительно phonewallclock; никакихdates/ID/offset/readings.
  Session.HistoryObserver.referenceTimeMillis default−1, Activity supplieswallclock только
  history_past_target. Demux вызывает reference толькоfresh exact+CRC/pad при!headerScan;
  prestarted/mismatch/retention files не получают time/value diagnostics. Clockverified=false,
  time_mapping_status=source_hypothesis_not_independently_verified всегда.
- **Hardware0.31 offsets0/1,1060ms/1276ms:**
  -0:ordinal11/1394B/86records,HRvalid86;encoded0=78/encoded255=0;stepzero82/nonzero4.
  -1:ordinal11/98B/5records,HRvalid5;encoded0=5/encoded255=0;stepzero5/nonzero0.
  В обоихminutealigned/civilzone/signedagree=true,agewithin_24h,lastnotfuture=true;
  fullpastmultisetretentionchecked/unchanged=true. По10quarantine→exact→3retention=14files;
  parsedровноordinal11. HR-present86/5 прежнегоэтапа **не**86/5реальныхизмерений:
  validitybit совместимс encoded0. Не превращатьencoded0 в0bpm или доказанныйmissing.
- **164 Java +45 Node PASS**,assemble/lint/editorPASS. Newsynthetic regressions:
  validity vs0/255/absence,14-bitflags/extra, signed/u32 boundaries/endwithoutoverflow,
  ±quarterhourzones/no doubleoffset, age/minuteedges, missingclock/empty→null,
  unsupported/noinventedcounts,exact-onlyclockread/quarantinefieldsabsent,safeprojection.
- Phoneinitiallylocked; пользователь вручнуюразблокировал и подтвердил2read-onlyсеанса.
  Socketclosed/keyabsent/MiFitnessprocessrestored послекаждого; финальнообаkeyfiles и
  productionlock absent,MiFitnesspid4556. BTreconnect не проверен. ACK8/5/SET/time/live/
  reset/unpair/clear/rawpersistence/clinicalimport/privateupload не выполнялись.
- **Далее:** независимаяtime/timezone/HRиstepsсверка, подтверждениеHR0semantics и
  фактическойгранулярности/gaps; затемcoverage/repeatarchive/productionintegration.
  Непротиворечиеphoneclock не timePASS, retentionдвухsnapshots не archivePASS;
  partial/time_values_verified=false/full_coverage_verified=false остаются.

## Продолжение 2026-11-13: same-ID past repeat, probe0.32

- Текущие **0.32/versionCode32/schema20**, установлен только isolated probe install-r.
  Новый opt-in `history_past_repeat OFFSET 1`; прежний past_target остаётся single-fetch.
  Production gates не менялись. После auth/exact identity/SPPv2 выполняются только
  **past8/2 → past8/3 → past8/3 → past8/2**, дважды один и тот же ID из первого snapshot.
- Session.historyPastRepeat использует общий historyTarget с repeat=true; второй fetch
  только после первого supported fresh exact/CRC/pad. Без смены offset/нового индекса,
  третьего fetch или автоматического retry. Один file deadline30s/4096messages на оба
  чтения; глобальные16files/512KiB включают indexwait, оба чтения и retention. Не сбрасывать
  бюджеты: существующий посторонний поток тоже в них входит. Index6s/session75s сохранены.
- Demux.repeat проверяет завершённость assembler/первого чтения и оставшийся budget;
  фаза repeat_wait, fetch_count2. Digest первого exact хранится только в RAM, второй
  сравнивается MessageDigest.isEqual; digest очищается после сравнения либо при close,
  включая cancellation/observer failure. Aggregates сравниваются по parser/counts/encoded
  categories/granularity, без clock/chunks. Same counts не подменяют byte equality.
- Activity сохраняет repeat_read_count по фактически завершённым exact-передачам даже
  при ошибке второго чтения. Comparison booleans null при незавершённом общем пути.
  При changed bytes — repeat_file_changed; при changed retention — retention_index_changed;
  unsupported второй — parser_unsupported. verified_target_repeat_sample всегда partial,
  history_time_values_verified=false/history_full_coverage_verified=false.
- **Hardware0.32:** offset0,1118ms: два exact ordinal11/15, по1394B/86records;2788B targetdata.
  Offset1,1459ms: exact ordinal11/15, по98B/5records;196B targetdata. В каждом bytesidentical,
  aggregateconsistent,fullpastmultisetretentionchecked/unchanged=true. По15completefiles:
  10quarantine → exact → 3quarantine → exact. Parser толькоordinal11/15.
- Три промежуточных файла теперь наблюдались в repeat_wait (ранее аналогичная по порядку
  группа попадала в retention_wait). Их источник/причинная связь с fetch не установлены.
  Уже queued, но не начатый assembler exact-файл неотличим от нового: доказано совпадение
  двух передач одного ID в одном сеансе после двух отправок, не causal matching или
  долговременная архивная сохранность. Межсеансовые ID не сравнивались; offset не cursor.
- **173 Java +48 Node PASS**,assemble/lint/editorPASS. Синтетические fixtures: sameID/twofetch/
  one-deadline, changedbytes при samecounts, changedcounts, rechunking, observed15file shape,
  prestarted quarantine, unsupportedfirst/second, CRC/pad/channel/plain/missing, sharedbyte/
  filebounds,timeout/cancel/EOF,retentionloss/duplicate/partial/flood,guards,privateprojection,
  digestwipe incl observerfailure. Observedshape/rechunking test-only добавлены послеhardware;
  runtime не менялся. Report только booleans/counts, без digest/IDs/raw/readings/dates.
- Оба socketclosed/keyabsent/MiFitnessprocessrestored; финально оба keyfiles и productionlock
  absent,MiFitnesspid7662. BTreconnect не проверен. Нет ACK8/5/SET/time/live/reset/unpair/
  clear/import/watermark/privateupload. Телефон был разблокирован, обхода lock не было.
- **Следующий реальный барьер:** независимая time/timezone/HR/steps сверка и смысл HR0,
  затем granularity/gaps/full-index coverage и архивный repeat между сеансами. Повтор
  одинаковых байтов сам по себе не подтверждает значения/время или clinical пригодность.

## Продолжение 2026-11-13: Kotlin history, фон и хранилища приложений

- `:band-protocol`, независимо от Java probe, реализует `verifyPastHistory(offset, repeat)`:
  past8/2 → выбранный v4 details8/3 → optional тот же ID8/3 → past8/2. Exact все7байт,
  CRC/pad, foreign/prestarted quarantine, full multiset retention с unknown/duplicates.
  Общий deadline30s,512KiB,16files,4096bulk messages; 2880record parser cap. Scope allowlist
  не разрешает generic history, ACK8/5,SET/live. Digest/ID/raw buffer очищаются.
- Возвращаются категории кодировок, не readings/timestamps. HR0/255 остаются отдельными
  encoded categories; timeAndValuesVerified/fullCoverageVerified/clinicalImportAllowed
  безусловно false. Ни patient ActivitySource, ни backend минуты ими не заполняются.
- **Аппаратно Kotlin library history_repeat offsets0/1 PASS**: auth/guard/battery/GET,
  две exact передачи, samebytes/sameaggregates/fullpastretention unchanged в каждом.
  Отчёт library runner schema2 — только фиксированные booleans. Keys/lock absent,
  socketclosed,MiFitnessprocessrestored; BTreconnect неизвестен. Это не causal matching,
  межсеансовый archive repeat или доказательство time/values/day totals.
- Пациент: отдельная read-only status periodic work вместо history work для Band9;
  CDM/permissions/актуальная binding до и под session mutex, connectedDevice FGS,
  60s cap, cancellation/idle/binding guards. Без минут, upload и watermark. Band6 путь
  сохранён. Keystore read не регенерирует отсутствующий alias, ciphertext сохраняется.
- Персонал: strict MAC/protocol-bound QR/key input, revalidation при выдаче, QR скрывается
  при background, отсутствующий alias без регенерации/fallback, non-destructive ошибки.
- **363 JVM PASS** (224patient/43staff/77protocol/19architecture), lint/обычные APK и
  instrumentation APK PASS; **50 Node PASS**, editor/whitespace checks PASS.
  Scoped code review выявил lifetime frame counter: после history мог падать обычный GET.
  Исправлено отдельными512ordinary/8192absolute budgets, regression на249battery reads
  → repeated history → battery/settings PASS. Последний counter fix аппаратно не повторён.
- **17 Android instrumentation PASS на SM-S731B/Android17**:3patient+14staff synthetic
  migration/Keystore/protocol persistence/reopen/tamper/missingalias. Отдельные
  `ru.kardiomonitor.patient.validation[.test]`/`ru.kardiomonitor.staff.validation[.test]`,
  сборки `-PpatientValidation=true`/`-PstaffValidation=true`, vanilla Application вместо
  production composition root. Рабочее patient APK/данные не заменялись; tests очищают
  только свои UUID/namespaced данные. Первый SDK runner crash исправлен test manifest
  uses-library, повтор PASS. Не считать это UI/Worker/CDM/Doze/processdeath/выдачаE2E.
- **Остаются BLOCKED:** независимые time/timezone/HR/steps/HR0 и gaps/fullcoverage,
  реальные суточные суммы, physical live stop, проверенный SET+backup/restore,
  аппаратный фон/reboot/CDM/reconnect и clinical E2E на отдельном фиктивном пациенте.
  Не включать gates по успешным storage/JVM/library repeat тестам.

Дальнейшие разделы — исходный диагностический снимок и карта работ; слова «пока не
подключены» описывают именно этот снимок, а не отменяют foundation выше. Успешные тесты
foundation не означают полной поддержки или аппаратного закрытия прежних BLOCKED.

Назначение: передать следующему разработчику/агенту факты, ограничения, точки интеграции,
порядок работ и критерии приёмки без зависимости от истории чата. Это документ продолжения
работы, **не заявление о готовой поддержке Band 9** и не разрешение выполнять любые команды
на личном браслете. Предложения ниже явно отделены от реализованного поведения.

## 1. Коротко: с чего продолжать

- Production поддерживает **Mi Smart Band 6**, а не Band 9. Простая замена имени/UUID не работает.
- Проверяется **обычная Xiaomi Smart Band 9, M2345B1, firmware 2.3.98**, не Active/Pro.
- Нужен отдельный **Bluetooth Classic BR/EDR, RFCOMM SPP, Xiaomi-протокол** вместо BLE/Huami 2021.
- Есть отдельный диагностический Android APK и оригинальный Java protocol core:
  [tools/band9-probe](../tools/band9-probe/README.md). В production они пока не подключены.
- На устройстве доказаны SPP v2, авторизация с проверкой watch HMAC, battery/device info,
  короткие live HR-сеансы, GET настроек HR, индексы истории, чтение двух выбранных
  details-сегментов и повторное чтение одного сегмента.
- **Не доказаны** полная история, числовые поминутные данные с корректным временем,
  today steps из summary, wear/sleep/charging, надёжная остановка live-сенсора,
  SET интервала, фон и клинические end-to-end сценарии.
- Ближайший шаг: доработать явное bounded-чтение остальных details-сегментов,
  не увеличивая ограничения и не отправляя history ACK; затем время/значения и summary.
- Главный журнал фактической приёмки: [чек-лист B9-01…B9-21](band9-validation-checklist.md).
  В нём PARTIAL означает только явно указанную часть проверки, а не весь сценарий.

## 2. Пользовательская задача и границы работ

Пользователь предоставил собственный Band 9 и Android-телефон через USB/ADB, попросил
исследовать публичную документацию и проверить флоу, уже работающие с Band 6.
Затем попросил сохранять результаты в отдельном чек-листе и продолжать проверку.

Полная поддержка должна включать оба Android-приложения (пациент/персонал), историю,
пульс, шаги, настройки, безопасную выдачу браслета, фон, сервер и панель. Успешный probe
не закрывает ни один production end-to-end сценарий автоматически.

Текущая стратегия: сначала доказать протокол на изолированном read-only стенде,
потом интегрировать через доменные порты, сохранив поведение Band 6.
Не использовать реального пациента как тестовый стенд. Данные бытового браслета
не являются медицинскими измерениями и не становятся пригодными для диагностики
после успешной технической интеграции.

## 3. Стенд и безопасный доступ

| Компонент | Подтверждённое состояние |
|---|---|
| Браслет | Обычная Xiaomi Smart Band 9, модель M2345B1 |
| Firmware | 2.3.98, подтверждена device info; guard probe проверяет точное совпадение |
| Телефон | Samsung Galaxy S25 FE, SM-S731B |
| Android | 17 / API 37 |
| Mi Fitness | `com.xiaomi.wearable`, 3.59.2i / versionCode 359002 |
| Доступ | USB/ADB, авторизованный телефон; на прошлых прогонах состояние `device` |
| Bluetooth | Уже существует bond; Classic BR/EDR ACL и SPP обнаружены |
| Probe | `ru.kardiomonitor.band9probe`, v0.16 / versionCode 16 / schema 4 |
| Build | JDK 21, Android SDK platform 36, AGP 9.1.0; wrapper из основного Android-проекта |

Полный MAC, auth key, аккаунты, QR и сырые файлы истории сюда не включены.
ADB serial не является протокольным параметром: перед работой выбрать нужный телефон
через `adb devices`, а не предполагать наличие прежнего устройства.

### 3.1. Обязательные запреты и ограничения

1. Не делать reset/unpair, перепривязку в Mi Fitness или firmware update ради теста.
2. Не выполнять `pm clear`, не удалять production-приложения/базы/данные пациентов.
3. Не посылать Band 9 команды Huami/Band 6, включая его правила ACK и ping.
4. **Не отправлять Xiaomi history ACK 8/5.** Транспортные SPP ACK — другое и нужны связи.
5. Не делать SET 8/11 до отдельной проверки формата, чтения исходного значения и плана восстановления.
6. Не публиковать ключ, nonce/HMAC/session keys, raw-пакеты, полный Mi Fitness log,
   timestamps/ID истории, серийник или медицинские данные.
7. Никаких неявных sync, fetch, ретраев, time/profile/workout writes или повышения лимитов,
   чтобы «получить PASS». Не отключать ID/CRC/padding-проверки при несовпадении.
8. Перед запуском телефон должен быть разблокирован. При блокировке попросить владельца
   разблокировать его; не обходить блокировку и не менять системный screen timeout.
9. После каждого прогона закрыть socket, удалить временный ключ и вернуть Mi Fitness.
   Работающий процесс Mi Fitness ещё не доказывает его Bluetooth-переподключение.
10. Не запускать несколько probe одновременно. Во время live не делать force-stop:
    Java `finally` не выполнится; отменять штатно и дождаться попытки stop.

### 3.2. Ключ: что уже работает, а что ещё нет

[band9-prepare-key.mjs](../tools/band9-prepare-key.mjs) локально читает лог
`/sdcard/Android/data/com.xiaomi.wearable/files/log/XiaomiFit.main.log` через ADB.
Выбирается ровно один ненулевой 32-hex кандидат, связанный со строкой обычного Band 9,
без Active/Pro и неоднозначности нескольких моделей. На стенде кандидат подтверждён
успешным watch HMAC; само наличие строки в логе не является проверкой ключа.

При `stage` ключ поступает через stdin в private `files/auth-key` probe с правами 600:
**ровно 32 ASCII hex символа без LF = 16 байт**. Значение не передаётся аргументом CLI,
через Intent или в отчёте. Runner удаляет файл и проверяет отсутствие в `finally`.
Это диагностический механизм, **не готовый production-процесс получения ключей**.
Лог может исчезнуть/ротироваться или стать недоступным на другом телефоне.
Массивы Java очищаются best-effort; полного стирания внутренних JVM/JCA копий и
JavaScript strings гарантировать нельзя.

## 4. Что уже проверено на железе

Подробные результаты и старые ошибки сохранены в [чек-листе](band9-validation-checklist.md).
Числа ниже — безопасные агрегаты, не дневник измерений.

| Проверка | Наблюдение | Что это не доказывает |
|---|---|---|
| SPP | Secure RFCOMM, ответ Version: channel 0, opcode 1, data type 0, prefix `[2,0,97]`; SPP v2 | Работу на других Band 9/firmware, Active/Pro |
| Auth | Fresh Xiaomi session, watch HMAC проверен, confirmation принят | Production enrollment/хранение/выдачу ключа |
| Battery/info | M2345B1 / 2.3.98; ранее 98%, позднее 97% | Визуальную сверку заряда на экране |
| Live HR | Два независимых 30-секундных сеанса, по 3 HR-события; первые HR примерно через 20–22 с | Стабильный длительный поток, фон, клиническую точность |
| Stop live | Команда stop отправлена, socket закрыт; новый сеанс успешен | ACK stop и независимую остановку датчика: `live_stop_acknowledged=false` |
| HR settings GET | 8/10: `disabled=false`, `interval=0` = Smart; поля присутствуют | Допустимость интервалов 1/5/10/30, SET и восстановление |
| History index | Today/past читаются; в последнем контрольном индексе 10 details v4, summary нет, past пуст | Отсутствие старой истории в Mi Fitness, полный архив |
| v0.13 sample | Первый выбранный details: 98 bytes, 5 записей, HR/steps presence 5/5; CRC/ID/padding PASS, index unchanged | Числовой HR/steps, время и весь день |
| v0.15 repeat | Тот же первый ID дважды в одной session: 98 bytes / 5 записей, агрегаты и внутренний SHA-256 совпали | Повторяемость всех файлов и глобальную сохранность истории |
| Свежий v0.15 sample перед 0.16 | 178 bytes / 10 записей, presence 10/10; CRC/ID/padding PASS, index unchanged | Что файл неизменяемый между независимыми сеансами |
| v0.16 next | Второй уникальный details по индексу: 66 bytes / 3 записи, presence 3/3; CRC/ID/padding PASS, index unchanged; 9 кандидатов пропущено | Что уже прочитаны остальные 8 кандидатов или их состав стабилен между сеансами |
| Полный bounded read | На индексе 10 > 8 корректный отказ `history_read_file_count_limit` до file request | Ошибку браслета или необходимость поднять лимит |
| Cleanup | После прогона временный ключ отсутствует, Mi Fitness process восстановлен, bond виден | Независимую проверку повторного ACL и всей истории Mi Fitness |

Все реальные file-read прогоны выполнялись **без 8/5 и без raw persistence**.
В последних успешных sample/repeat retention сравнивался внутри того же соединения.
Порядок индекса не означает «новейший/старейший», пока это отдельно не доказано.

### 4.1. История ошибок — не повторять уже снятые предположения

- v0.6 требовал ровно один details и одну summary; отказал при нескольких details.
  Это ограничение диагностики, не доказательство поломки протокола.
- v0.7–0.9 разрешили до 8 уникальных daily-файлов и дедупликацию, затем summary-first.
  Получались CRC-valid файлы с несовпадающим запрошенным ID. Иногда различался timestamp,
  иногда все части ID. Отсутствие protobuf receipt 8/3 само по себе не объясняет mismatch.
- **Root cause прежних ID mismatch не установлен.** Не считать, что его исправил parser fix,
  отсутствие ошибки в новом sample или переход к одному запросу на соединение.
- Первый совпавший details отвергался как `unsupported_details_header`.
  В v0.13 по upstream установлено: последний полубайт 6-byte v4 header зарезервирован,
  это не двенадцатая группа. Ненулевой nibble не должен отвергать корректный файл.
  Regression прогоняет все 16 значений reserved nibble.
- Ранний parser Version фильтровал opcode/data type без оснований. Исправлен только
  channel 0; magic/length/channel/epilogue и ограничения остальных каналов сохранены.
- Необоснованное требование peer session version ровно 1.0.0 удалено; проверки TLV сохранены.
- Нельзя переносить старое «retention не достигнут» из неудачных v0.7–0.9 на новые успешные
  sample. Но новые sample не превращают старые ошибки в объяснённые.

## 5. Карта диагностической реализации

| Файл | Назначение |
|---|---|
| [ProbeActivity.java](../tools/band9-probe/app/src/main/java/ru/kardiomonitor/band9probe/ProbeActivity.java) | Bonded selection, Android socket/lifecycle, deadlines, mode dispatch, aggregate report |
| [Session.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/Session.java) | Negotiate/auth/query/live/history, allowlist, SPP ACK/sequence, retention/repeat |
| [Wire.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/Wire.java) | Bounded SPP v1/v2 codecs, frame CRC и version metadata |
| [Proto.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/Proto.java) | Собственный bounded protobuf subset, presence и strict parsing |
| [Auth.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/Auth.java), [Crypto.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/Crypto.java) | HKDF/HMAC, CCM/CTR, secret lifecycle |
| [HeartRateSettings.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/HeartRateSettings.java) | Только GET settings с сохранением presence |
| [HistoryIndex.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/HistoryIndex.java) | Today/past builders и безопасные counts/categories |
| [HistorySelection.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/HistorySelection.java) | Private ID snapshot, dedup, summary-first, bounded samples, multiset comparison |
| [HistoryFile.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/HistoryFile.java) | Chunk assembler, ID/pad/CRC, safe metadata, private repeat digest |
| [DailyFileSummary.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/DailyFileSummary.java) | Aggregate-only details v4 / summary v5, не production decoder минут |
| [VectorTests.java](../tools/band9-probe/protocol/src/test/java/ru/kardiomonitor/band9probe/protocol/VectorTests.java), [HistoryTests.java](../tools/band9-probe/protocol/src/test/java/ru/kardiomonitor/band9probe/protocol/HistoryTests.java) | Synthetic protocol fixtures/scripted peer |
| [activity-safety.test.mjs](../tools/band9-probe/protocol/src/test/activity-safety.test.mjs) | Lifecycle, deadlines, permissions, отсутствие raw persistence |
| [band9-run-readonly.mjs](../tools/band9-run-readonly.mjs) | Lock/active guard, stage key, stop Mi Fitness, запуск, ожидание, cleanup |
| [band9-prepare-key.mjs](../tools/band9-prepare-key.mjs) | Локальная подготовка model-associated key без вывода значения |

### Режимы v0.16

- `transport`: version, без чтения ключа.
- `auth`: negotiate/auth, без обычной полной инициализации Gadgetbridge.
- `read`: auth + battery/device info; steps пока недоступны, итог partial.
- `live`: отдельный 30-секундный эксперимент с start/stop, **не read-only**.
- `settings`: один GET 8/10, без SET.
- `history_index`: metadata-only today/past, без файлов.
- `history_read`: все разрешённые уникальные today-файлы, максимум 8; иначе отказ до download.
- `history_sample`: максимум одна summary + первый details; partial с omitted count.
- `history_sample_next`: **жёстко задан только второй уникальный details** (`sampleDetailOffset=1`),
  summary пропускается; это не «следующая страница» и не persistent cursor.
- `history_repeat`: sample + повтор первого выбранного ID внутри той же session;
  сравниваются safe projection и private SHA-256; digest не публикуется.

Read-only runner допускает только settings/history режимы, не `live`, `auth` и произвольные
команды. В sample-режимах дополнительно разрешён один 8/2 для metadata past, **без fetch past**.
Пустой результат/пропуски/unsupported layout не считаются доказательством поддержки.

## 6. Факты wire-протокола, на которые опирается код

### 6.1. Transport и crypto

- Standard SPP UUID: `00001101-0000-1000-8000-00805f9b34fb` (0x1101).
- Android: secure `createRfcommSocketToServiceRecord`, только уже bonded device;
  `cancelDiscovery()` перед connect. Probe не запускает discovery и pair/unpair.
- Сначала SPP v1 version request, затем по первому байту ответа выбор версии.
  Реальный Band 9 отвечает v2. Нет fallback к Huami/BLE после ошибки.
- Для v2: session start/TLV, transport sequence/ACK, protobuf channel 1,
  encrypted Activity channel 5; channel 2 не подменяет историю.
- Auth: `1/26` fresh phone nonce (16 bytes), watch nonce (16) и HMAC proof (32);
  HKDF-SHA256, salt `phoneNonce || watchNonce`, info `miwear-auth`, output 64 bytes.
  Receive key = bytes 0..15, send key = 16..31, receive/send prefixes = 32..35 / 36..39.
- Watch proof проверяется constant-time как HMAC(receive key, `watchNonce || phoneNonce`).
  `1/27` содержит phone proof и encrypted phone info; CCM tag 4 для phone info.
- После auth в SPP v2 используется AES-CTR, IV равен ключу соответствующего направления;
  cipher перезапускается на каждом сообщении. Это факт опубликованного протокола,
  **не рекомендация для новой криптосистемы**. Per-message cryptographic integrity
  у этого v2 формата не заявляется; CRC не заменяет MAC.
- SPP v1/CCM покрыт synthetic tests, но history read guard требует SPP v2.
- Полные nonce layout, protobuf fields и canonical fixtures брать из локальных
  [Auth.java](../tools/band9-probe/protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/Auth.java)
  и тестов, не восстанавливать «по памяти».

### 6.2. Команды

| Type/subtype | Назначение | Статус |
|---|---|---|
| 1/26, 1/27 | Xiaomi auth | Hardware PASS |
| 2/1, 2/2 | Battery / device info | Hardware PASS |
| 8/10 | GET HR settings | Hardware PASS |
| 8/11 | SET HR settings | Не реализован в probe, не проверен на железе |
| 8/1 | Today history index | Hardware PASS |
| 8/2 | Past history index | Metadata PASS, past оказался пустым |
| 8/3 | File request по 7-byte ID | Выбранные details PASS |
| 8/5 | History file ACK | **Не отправлялся; запрещён в текущем эксперименте** |
| 8/45, 8/46, 8/47 | Live start / stop / stats | HR приходит; stop ACK/фактическая остановка не доказаны |

GET HR settings: `disabled` и `interval` — разные поля. **0 = Smart, а не Off**.
Отсутствие поля не равно false/0. Ответ не содержит подтверждённого списка допустимых
интервалов; 1/5/10/30 минут нельзя объявлять supported только по UI Band 6.
В комментариях upstream schema встречался сдвиг номеров history commands: ориентир —
реальные service constants/builders и проверенные wire fixtures, не отдельный комментарий.

### 6.3. History transport и ID

- Descriptor: 7 bytes; первые 5 относятся к timestamp/timezone, но в probe **не декодируются**.
  Предпоследний byte = version. Последний flags: type bit 7, subtype bits 2..6, detail bits 0..1.
- Разрешённые для download категории: `(type=0, subtype=0, detail=0, version=4)` details
  и `(0,0,1,5)` summary. Unknown categories не скачиваются.
- Today request: 8/1 с Health.field5 → field1=0; past request: 8/2 без Health body.
  Response file IDs: Health.field2 bytes. Идентичные ID дедуплицируются для download,
  полный исходный multiset остаётся для retention comparison.
- Bulk: SPP v2 Data type 3, channel 5, opcode 2, AES-CTR; после decrypt chunk header
  `total:u16LE || number:u16LE`, нумерация с 1, total не меняется.
- Assembled file: `ID7 || zeroPadding1 || body || CRC32LE4`.
  CRC32/ISO-HDLC покрывает всё кроме последних 4 bytes. Сначала integrity/identity,
  потом parser; никакого ремонта CRC, смещения ожидаемого ID или принятия чужого файла.
- Application chunk duplicates/gaps/reset отклоняются. Повтор последнего SPP frame
  только ACK-ается, не добавляется второй раз; uint8 sequence wrap обработан.
- Protobuf receipt 8/3 не является file body, не требует history ACK и может не
  наблюдаться до завершения файла. Он не заменяет проверки файла.

### 6.4. Details v4 и summary v5

**Details v4:** 6-byte nibble bitmap, 11 групп с ширинами
`2,1,1,2,1,1,2,1,1,2,2`; nibble presence bit 3, validity bits 2..0.
Последний reserved nibble игнорируется, а не создаёт 12-ю группу.
Шаги — третья позиция группы 0; HR — первая позиция группы 4.
Условный дополнительный byte для second-valid/bit14 группы 0 расположен после stress
(группа 8), перед последними двумя v4 группами. Minute advance 60 с следует из upstream,
но реальное временное выравнивание ещё не сверено.

Текущий parser считает записи и **presence**, не экспортирует числовой HR/steps или
время. Неизвестные kind/wear не интерпретируются. Truncation, неподдержанный размер,
zero-width/overflow → `unsupported_*`, без догадок и нулевых измерений.

**Summary v5:** принимается только ровно **73 bytes** всего файла:
`ID7 + pad1 + bitmap4 + 57 fixed-slot bytes + CRC4`.
Steps = u32LE по absolute offset 12, validity = bit 7 byte 8.
Числовой `today_steps` появляется только после CRC/ID/pad/length/validity.
Такая сводка **не получена на железе с совпадающим ID**; текущий индекс её не содержит.
Не выводить суточный итог из realtime steps или суммы неполной history selection.

### 6.5. Лимиты, статусы и смысл сохранности

- Index: максимум 256 ID / 1792 bytes, длина кратна 7.
- Обычный bounded read: максимум 8 уникальных разрешённых файлов, максимум одна summary;
  больше файлов/две разные summary → отказ до скачивания.
- Sample: максимум 2 выбранных файла; next — 1; repeat дополнительно читает первый.
- Общий file-byte budget 512 KiB; максимум 2048 chunks/file, 2880 details records/file.
- Bulk payload cap 64512 bytes включается только во время history read;
  обычный payload и protobuf cap 4096 bytes сохраняются.
- Session cap: 8192 received frames в history, обычно 512; per-file loop 4096 messages.
- Absolute watchdog **75 с**, file phase **30 с**, index/обычный read **6 с**, auth **12 с**;
  phase deadline не может продлить общий срок.
- `verified` → root ok; `verified_sample`, unsupported, no files или changed index → partial;
  integrity/order/timeout → error; отмена → cancelled. В report schema 4 хранится projection,
  не raw history. Пропущенные файлы должны быть явно посчитаны.
- `retention_index_unchanged=true`: multiset today index совпал внутри session.
  Это не доказательство отсутствия потери всех данных, дублей в приложении или сохранения
  всей истории Mi Fitness. Changed index может означать появление нового файла, не удаление.
- `repeat_bytes_identical=true`: совпал private SHA-256 проверенного файла, а не только
  counts; digest и ID не публикуются. Между независимыми сеансами это не проверялось
  как неизменяемость каждого файла.

## 7. Где нужна production-интеграция

Состояние прочитано из текущего рабочего дерева, а не только из старой документации.
Android сейчас включает `:app`, `:band-protocol`, `:architecture`, `:staff`.
Mi Band 6 core/GATT вынесены в [band-protocol](../android/band-protocol/), ACL остаётся
в приложении. Не искать удалённые старые пути core внутри `app` и не восстанавливать их.

### 7.1. Пациент: transport, routing и доменные контракты

| Точка | Текущий контракт / нужное изменение |
|---|---|
| [AppContainer.kt](../android/app/src/main/java/ru/kardiomonitor/app/AppContainer.kt) | Один `HuamiBand` передаётся и как `BandLink`, и как `ActivitySource`; заменить wiring на model-aware routing без двух независимых соединений |
| [HuamiBand.kt](../android/app/src/main/java/ru/kardiomonitor/platform/huami/HuamiBand.kt) | Mi Band 6 ACL, BLE/GATT, Huami auth; сохранить отдельным драйвером, не добавлять Xiaomi bytes в Huami |
| [BandLink.kt](../android/app/src/main/java/ru/kardiomonitor/features/band/domain/BandLink.kt) | Open/close/battery/SET interval; новый Xiaomi ACL должен соблюдать lifecycle и safe errors |
| [ActivitySource.kt](../android/app/src/main/java/ru/kardiomonitor/features/monitoring/domain/ActivitySource.kt) | `minutesSince`, `liveHeartRate`, `stepsToday`; probe эти production-порты не реализует |
| [SingleBandConnection.kt](../android/app/src/main/java/ru/kardiomonitor/features/band/connection/SingleBandConnection.kt) | Mutex/единый session owner, lifecycle, battery log; автоматически применяет сохранённый interval при open |
| [BandCredentials.kt](../android/app/src/main/java/ru/kardiomonitor/features/band/domain/BandCredentials.kt), [Band.kt](../android/app/src/main/java/ru/kardiomonitor/features/band/domain/Band.kt) | Сейчас address/key без явного model/protocol; решить routing и backward-compatible identity |
| [SharedPreferencesBandRepository.kt](../android/app/src/main/java/ru/kardiomonitor/features/band/data/SharedPreferencesBandRepository.kt) | Keystore-encrypted key, pairedAt, legacy interval storage; нужны миграции без потери Band 6 привязок |

**Предлагаемое направление, ещё не реализовано:** чистый Xiaomi protocol core без Android,
отдельный cancellable RFCOMM adapter и Xiaomi ACL, model/capability-aware routing в
композиционном корне. Решить модульную границу по текущим архитектурным правилам;
не подключать диагностическую Activity/runner/private-file staging в production.

При отмене coroutine блокирующий socket read/connect должен действительно завершаться
через close socket/timeout. Не оставлять IO на UI thread, утечки reader thread или старый
socket после переключения браслета. Запросы, подписки и auth принадлежат одному session;
владение не должно разделиться между `BandLink` и `ActivitySource`.

Проверку модели нельзя заменить только именем Bluetooth или 16-byte форматом ключа:
оба протокола имеют внешне похожие credentials. Не пробовать по очереди чужие команды
на неизвестном устройстве. Capability/model detection и политика firmware требуют
явного дизайна и тестов; проверка M2345B1/2.3.98 пока намеренно узкая.

### 7.2. Settings: важная несовместимость модели

[HeartRateMeasurementInterval.kt](../android/app/src/main/java/ru/kardiomonitor/features/band/domain/HeartRateMeasurementInterval.kt)
сейчас задаёт Off/Every(1..120), UI 1/5/10/30/Off. Legacy preferences кодируют Off как 0.
Для Xiaomi **0 = Smart** при отдельном `disabled`. Нельзя просто передать имеющееся
число в 8/11: это изменит смысл настройки и может непреднамеренно включить измерения.

Нужно решить представление Smart/Off/unknown/unconfigured, model-specific capabilities,
миграцию legacy 0 для Band 6, GET/readback и восстановление прежней конфигурации.
Отсутствующие поля GET должны остаться отсутствующими; SET не должен сбрасывать соседние
alarm/feature toggles из-за protobuf defaults. До проверки SET выключить его неявное
применение к Band 9 при open; Band 6 поведение сохранить.

### 7.3. История, локальная база и граница пациента

- [SyncHistory.kt](../android/app/src/main/java/ru/kardiomonitor/features/monitoring/historysync/SyncHistory.kt)
  вызывает source → save → sync status. Нельзя вернуть один sample как успешную полную
  синхронизацию: продвинется last-minute watermark и можно навсегда пропустить более ранние сегменты.
- [HistorySyncStart.kt](../android/app/src/main/java/ru/kardiomonitor/features/monitoring/domain/HistorySyncStart.kt)
  берёт следующую минуту после последней сохранённой, иначе 7 дней назад, и ограничивает
  начало минутой pairedAt. Нужны точные времена Xiaomi и фильтрация распарсенных записей,
  даже если файл физически содержит более старые минуты.
- [ActivityMinute.kt](../android/app/src/main/java/ru/kardiomonitor/features/monitoring/domain/ActivityMinute.kt)
  содержит Instant, обязательные StepCount/kind/intensity и nullable HR. Xiaomi missing/invalid
  steps/kind/intensity нельзя молча свести к 0/«носит». Решить политику неизвестных данных
  и при необходимости согласованно изменить доменную модель, persistence/API/аналитику.
- [ActivityRecords.kt](../android/app/src/main/java/ru/kardiomonitor/platform/huami/ActivityRecords.kt)
  маппит **Band 6** kind 115/118/120 как not-worn/charging/sleep. Не использовать эти коды
  для Xiaomi и не давать неизвестным Xiaomi-кодам семантику старого драйвера.
- [SqliteActivityHistory.kt](../android/app/src/main/java/ru/kardiomonitor/features/monitoring/data/SqliteActivityHistory.kt):
  проверить idempotent save/re-read, совпадения minute keys, late data, повторные/изменённые файлы,
  корректное разделение данных при смене браслета. Session/file cursor не равен last minute.
- [ADR-0011](../android/docs/adr/0011-band-reissue-keeps-key.md): не импортировать чужие минуты
  до новой выдачи. Повторная выдача с тем же ключом несёт остаточный риск; не заявлять,
  что поддержка Band 9 автоматически решает отзыв доступа старого телефона.
- Текущий probe не хранит минут, не импортирует их и не доказывает отсутствие дублей
  в SQLite/сервере. Summary/day totals должны оставаться отдельными от minute history.

### 7.4. Персонал, поиск, QR и ключи

- [BleBandDiscovery.kt](../android/staff/src/main/java/ru/kardiomonitor/staff/platform/band/BleBandDiscovery.kt)
  ищет `nearestMiBand6()` через BLE/name/RSSI. Это не discovery Band 9 по BR/EDR.
  Нужен явный выбор модели/устройства, подтверждение идентичности и обработка нескольких кандидатов;
  «самый близкий по BLE» и совпадение имени не являются доказательством правильного браслета.
- [MiBandInspector.kt](../android/staff/src/main/java/ru/kardiomonitor/staff/platform/band/MiBandInspector.kt)
  открывает MiBand6/GATT и **всегда записывает interval** после read. Для Band 9 такой флоу
  запрещён до проверки SET и должен использовать отдельный model-aware inspector.
- Текущий [staff PairingCode.kt](../android/staff/src/main/java/ru/kardiomonitor/staff/features/issuance/domain/PairingCode.kt)
  генерирует v1 `kardio;<MAC>;<key>`, без model/protocol.
- [patient PairingCode.kt](../android/app/src/main/java/ru/kardiomonitor/features/band/pairing/PairingCode.kt)
  понимает legacy credentials и v2 с `s=` HTTPS server / `e=` enrollment code.
  Нельзя объявлять QR v2 уже содержащим модель или автоматически выпускать новый несовместимый формат.
- Решить backward-compatible encoding model/protocol (и при необходимости capability/version),
  storage в inventory/issuance/patient, ручной ввод и маршрутизацию старых QR без молчаливой
  интерпретации Band 9 как Band 6. Не добавлять новые секреты в логи/QR preview/analytics.
- Повторно проверить применимость существующего Xiaomi cloud/key workflow к Band 9.
  Наличие диагностического ключа из лога не подтверждает выдачу/ротацию/отзыв ключей в продукте.
- Сохранить Keystore-подход пациента/персонала: [ADR-0010](../android/docs/adr/0010-staff-secrets-in-keystore.md),
  [ADR-0012](../android/docs/adr/0012-patient-secrets-in-keystore.md).

### 7.5. Фон, live request, сервер и панель

- Сохранить единое соединение при конкуренции manual sync, Worker, live и settings;
  тестировать loss/reconnect, отмену, Bluetooth off/on, process death, screen lock и отсутствие прав.
- [CompanionBandAssociation.kt](../android/app/src/main/java/ru/kardiomonitor/features/band/pairing/CompanionBandAssociation.kt)
  уже использует CDM BluetoothDeviceFilter по MAC, не только BLE-фильтр. Её не нужно
  переписывать из предположения, что она BLE-only, но аппаратную совместимость с Band 9
  и Android background/FGS restrictions ещё надо проверить.
- Сценарий doctor live request описан в [ADR-0013](../android/docs/adr/0013-clinic-live-heart-rate-request.md):
  ассоциация, видимое connectedDevice FGS-уведомление, stop пациентом, отчёт/outbox.
  Probe foreground Activity не доказывает работу этого сценария в фоне.
- Не переносить Band 6 keepalive каждые 12 с в Xiaomi. Проверить Xiaomi длительный live,
  нужную подписку/поддержание, stop/timeout/cleanup на своей прошивке.
- Через фиктивного пациента проверить minute upload, live upload, cursor/idempotency,
  server timezone/day boundaries, суточную статистику, нормы/тренировочные минуты,
  напоминания и wear-dependent правила, запрос врача и оповещения в панели.
- Не менять backend schema «на всякий случай»: определить, достаточно ли существующих
  нормализованных минут/HR или реально нужны model/source/quality/missing поля;
  при необходимости менять Android/backend/frontend контракт согласованно.

## 8. Нерешённые вопросы и порядок реализации

### Этап A — завершить доказательство history-протокола

1. Обобщить explicit sample на bounded порции/смещение без увеличения byte/frame/time caps.
   Сегодня `history_sample_next` всегда берёт только второй details и не продвигает cursor.
   Считать выбранные/пропущенные файлы, дедуплицировать и сохранять полный snapshot для retention.
2. Покрыть selection-тестами duplicates, unknown types, summary среди details, multiple summaries,
   пустой/out-of-range offset и изменившийся порядок/состав индекса. Обновить scripted peer tests
   для самого нового режима, не только constructor selection.
3. Прочитать другие details с exact ID/CRC/padding; доказать несколько последовательных file requests
   в одном соединении. Проверить repeat нескольких ID и независимые сеансы с корректным учётом
   изменяемых файлов. Не обходить прежний mismatch автоматическим принятием другого ID.
4. Установить точный формат timestamp/timezone и начало записей по wire-фактам/fixtures;
   проверить UTC/local offset, полночь, смену даты/зоны, границы минут и отсутствие double offset.
5. Добавить decoder числовых значений с validity, signed/unsigned/overflow checks и отдельными
   безопасными подтверждениями владельца. Не превращать invalid HR в измеренный 0.
6. Дождаться/найти корректный summary v5 с совпадающим ID без искусственных write-команд.
   Сверить today steps с экраном при зафиксированном времени; отдельное сравнение владельцем
   требует его участия. Не расширять fixed-length parser без подтверждённого формата.
7. Проверить поведение past index/архива, offline-накопление, charging/sleep/not-worn и сохранность
   для Mi Fitness; пустой индекс не закрывает тест. Отдельно изучить семантику 8/5,
   **не включать его ради успешного download**.

### Этап B — live и настройки на стенде

1. Проверить длительный live, начало/первое измерение/пропуски, повторный старт, cancel и link loss.
2. Независимо подтвердить фактическую остановку сенсора и поведение 8/46, не требуя выдуманного
   ACK, если протокол его не предусматривает. Согласовать наблюдаемый критерий остановки.
3. Исследовать SET 8/11 и supported values на этой firmware. До любой записи сохранить GET
   с presence и обеспечить гарантированную попытку восстановления/проверки readback.
4. Проверить неверный ключ без вреда bond только отдельным согласованным экспериментом;
   сейчас это synthetic rejection, не hardware PASS.

### Этап C — production foundation и совместимость

1. Утвердить модель идентификации/routing/capabilities, QR/storage migration, semantics Smart/Off.
2. Выделить/переиспользовать protocol core, добавить Android RFCOMM и Xiaomi ACL;
   сохранить Huami и общий single-session контракт, не дублировать crypto без причины.
3. Реализовать complete/resumable history и нормализацию минут без ложного progress/watermark.
4. Подключить patient/staff pairing/inspection/settings/connection flows, ошибки/permissions,
   firmware guard и понятные unsupported states; не рекламировать непроверенные возможности.
5. Обновить архитектуру/ADR/документацию ровно по принятым решениям.

### Этап D — интеграционная приёмка и выпуск

1. Отдельный тестовый сервер, фиктивный пациент, журнал выдачи и безопасное разделение данных.
2. Ручная/фоновая история, screen lock, несколько суток, live request/stop/outbox, потеря связи.
3. Переходы Band 6 → Band 9 → Band 6, legacy QR/preferences, повторная выдача/смена пациента,
   отсутствие чужих минут и утечки ключа. Не обещать key revocation, пока она не реализована.
4. Контроль регрессий Band 6 и соответствующих server/frontend сценариев.
5. Фиксировать PASS только по наблюдаемым данным с версией приложения/firmware/стендом.

## 9. Проверки кода и воспроизводимый запуск

### 9.1. Последнее известное состояние валидации

- v0.16: **77 Java protocol tests PASS**, assembleDebug/lintDebug PASS, APK signature v2 verified.
- На последнем изменении прогнаны **11 Node tests** (7 runner + 4 activity safety), PASS.
  Ещё 3 key-preparation tests проходили ранее; поэтому журнал приводит совокупные 14.
  Для нового этапа лучше прогнать все 14 одной командой ниже, а не переписывать историю как свежий запуск.
- IDE problems не показал ошибок в изменённых Java/JS-файлах. Это не замена компиляции/host/hardware tests.
- Synthetic peer проверяет криптографию, allowlist, CRC/ID/chunks/limits, ошибки и cleanup;
  он **не заменяет настоящий Band 9**.
- Gradle может показывать deprecation warnings, в том числе `android.useAndroidX=false`;
  не исправлять несвязанные настройки ради документации/протокольного эксперимента.
- Ранее сборка без ANDROID_HOME отказалась из-за SDK location; повтор с SDK path успешен.
  Не добавлять зависимости, если проблема только в SDK env.

### 9.2. Host validation (из корня репозитория)

```sh
ANDROID_HOME="$HOME/Android/Sdk" ./android/gradlew --no-daemon --offline --console=plain \
  -p tools/band9-probe :protocol:test :app:assembleDebug :app:lintDebug
node --test tools/band9-prepare-key.test.mjs tools/band9-run-readonly.test.mjs \
  tools/band9-probe/protocol/src/test/activity-safety.test.mjs
"$HOME/Android/Sdk/build-tools/37.0.0/apksigner" verify --verbose \
  tools/band9-probe/app/build/outputs/apk/debug/app-debug.apk
```

SDK path/build-tools version — параметры локального стенда, не требования самого протокола.
В этой среде были build-tools 36.0.0 и 37.0.0, но не 35.0.0.
Protocol tests запускает настоящий JavaExec `:protocol:vectorTest`, зависимость `:protocol:test`;
отсутствие JUnit discovery не означает, что векторные тесты пропущены.
Никаких зависимостей production эти команды не меняют.

### 9.3. Следующий аппаратный read-only прогон

Только после выбора верного телефона, проверки unlock/USB/BT и согласованного режима.
Не использовать эти команды как пакет для автоматического запуска всех режимов.

```sh
adb devices
# Задать SERIAL авторизованного тестового телефона, не auth key.
adb -s "$SERIAL" install -r tools/band9-probe/app/build/outputs/apk/debug/app-debug.apk
node tools/band9-run-readonly.mjs "$SERIAL" history_index
# Затем один выбранный режим, например:
node tools/band9-run-readonly.mjs "$SERIAL" history_sample_next
```

Установить только probe; не устанавливать/не очищать patient/staff APK на личном стенде.
Bluetooth runtime permissions выдаются системе для probe (либо через диалог по инструкции
в [README](../tools/band9-probe/README.md)); доступ к USB/ADB не заменяет permissions Android.

Runner проверяет unlock и предыдущий `status:running`, подготавливает ключ, останавливает
Mi Fitness, запускает Activity, ждёт финальный report (до 90 polling-попыток) и выполняет
cleanup. `am start -W` не означает завершение протокольного теста.
Не обходить `another_probe_is_running` удалением отчёта без проверки состояния.

После завершения отдельно проверить:

```sh
adb -s "$SERIAL" shell run-as ru.kardiomonitor.band9probe test ! -e files/auth-key
adb -s "$SERIAL" shell pidof com.xiaomi.wearable
```

Финальный report хранится в private `files/report.json` probe и перезаписывается;
его safe aggregate projection можно использовать для протокола проверки.
Не собирать общий logcat или весь Mi Fitness log в артефакт.
Live запускать отдельно по [README](../tools/band9-probe/README.md), не через read-only runner.

## 10. Definition of Done полной поддержки

- [ ] Явно зафиксирована поддержанная модель/firmware; Active/Pro не включены без своих тестов.
- [ ] Production routing выбирает верный transport/protocol без опасного probing/fallback.
- [ ] Patient и staff проходят pairing/inspection/QR/key storage/migration без регрессии Band 6.
- [ ] Полные числовые минуты имеют доказанные timestamp/timezone/validity и фильтр pairedAt.
- [ ] Синхронизация большого числа файлов возобновляется без пропусков и дублей, не удаляет историю.
- [ ] Today steps проверены со screen/reference; partial history не подменяет daily total.
- [ ] Wear/charging/sleep/unknown отражаются корректно либо явно unsupported без ложной аналитики.
- [ ] Smart/Off/Every представлены правильно; SET/readback/restore проверены на Band 9.
- [ ] Live start/stream/stop/cancel/reconnect проверены, в том числе FGS и экран блокировки.
- [ ] Фоновая история и doctor request работают на выбранных Android версиях и правах.
- [ ] Server/panel получают только данные нужного пациента; повторы идемпотентны, quality/missing
  не искажают графики, нормы, напоминания и оповещения.
- [ ] Hardware-negative/lifecycle tests и Band 6 regression пройдены, секреты не попадают в logs/backups.
- [ ] Чек-лист, архитектурные решения, пользовательские инструкции и ограничения обновлены.

## 11. Источники и состояние репозитория

Основные локальные источники:

- [Чек-лист и журнал аппаратных проверок](band9-validation-checklist.md).
- [README probe: режимы, wire format, safe report, фиксированные upstream-ссылки](../tools/band9-probe/README.md).
- [Android architecture](../android/docs/architecture.md), [testing](../android/docs/testing.md),
  [Band 6 protocol](../android/docs/ble-protocol.md).
- [ADR-0002: сохранять историю](../android/docs/adr/0002-keep-history-on-band.md),
  [ADR-0003: одно соединение](../android/docs/adr/0003-single-band-connection.md),
  [ADR-0007: staff/multimodule](../android/docs/adr/0007-staff-app-multimodule.md),
  [ADR-0008: ключи Xiaomi](../android/docs/adr/0008-band-keys-from-xiaomi-cloud.md).

Публичные wire-факты изучались по Gadgetbridge/Codeberg, snapshot commit
**a047f5a1fe3067f3c685dca394b734bc11b79248**:

- [MiBand9Coordinator: BT_CLASSIC](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/xiaomi/watches/MiBand9Coordinator.java).
- [XiaomiAuthService](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiAuthService.java).
- [DailyDetailsParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/DailyDetailsParser.java).
- Остальные pinned ссылки на SPP, index/file fetcher, summary и proto schema находятся в
  [источниках README](../tools/band9-probe/README.md#источники-фактов-протокола-без-включения-их-кода).
- Crypto fixtures: RFC 5869, RFC 3610, NIST SP 800-38A и независимые Node/OpenSSL vectors.

Gadgetbridge implementation/schema не vendored, не linked, сгенерированные классы не включены.
Локальный probe написан самостоятельно по wire-фактам; это не заявление о clean-room
процессе с раздельными командами и не blanket licensing clearance. При дальнейшем
заимствовании нужно отдельно учитывать лицензию и не копировать upstream-код в проект.

Рабочее дерево уже содержит **много несвязанных изменений**, включая Android refactoring,
backend и документацию. Не откатывать/не форматировать/не коммитить их вместе с Band 9.
Band 9 probe, утилиты и чек-лист на последней проверке были untracked; они физически
сохранены, но **пока не закреплены отдельным git-коммитом**. Перед переносом работы в
другой checkout убедиться, что новые файлы действительно переданы. Коммиты в ходе этих
аппаратных проверок не создавались.

Отмеченный в чате файл `training-minutes-exceeded-plan.md` отсутствует и не является
источником контекста Band 9. Работа с ним не требуется для продолжения этого плана.

Этот документ фиксирует состояние, а не автоматически обновляющуюся спецификацию:
после каждого нового эксперимента обновлять [чек-лист](band9-validation-checklist.md),
[README probe](../tools/band9-probe/README.md) и относящиеся к результату разделы здесь.
