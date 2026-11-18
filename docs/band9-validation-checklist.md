# Проверка Xiaomi Smart Band 9

Дата начала: 2026-11-12. Это протокол экспериментальной совместимости, а не заявление
о поддержке Band 9 приложениями KardioMonitor. Результаты Band 6 не переносятся автоматически.

Контекст для продолжения разработки: [полная реализация поддержки Band 9](band9-implementation-context.md).

## Последнее продолжение: 2026-11-18 — sample-before-reference контроль

С разрешения владельца реализован и выполнен один обратный контроль: один явный today
`offset0`, два exact чтения → полный same-source retention → close Bluetooth → restore
Mi Fitness → до5мин на независимый tooltip → сравнение с образцом только в RAM.

| Проверка | Результат | Предел доказательства |
|---|---|---|
| Exact sample до новой синхронизации Mi Fitness | PASS в узком scope | Два одинаковых файла/агрегата и unchanged multiset; без ACK8/5, summary, fallback и обхода |
| Разделение этапов | PASS | Сокет закрыт, ключ/request удалены до sample_ready; Mi Fitness восстановлен до приглашения пользователя |
| Независимый tooltip после синхронизации | PASS для capture | Две устойчивые выборки текущей точки, без эталона из декодированного пульса |
| Сверка с удержанным образцом | BLOCKED | record_located=false, timezone_matches=true, HR/steps=null. Направление «раньше/позже образца» пока не выделяется |
| Cleanup | PASS | sample_memory_cleared=true; key/reference/capture/reverse request/signal/tmp/lock absent. Mi Fitness enabled/process запущены, task-owned ADB helper остановлен; BT reconnect не проверен |
| Validation | PASS | 113 protocol +19 architecture JVM,105 Node,5 synthetic instrumentation, protocol lint/isolated APK |

**Вывод:** простой смены порядка получения эталона недостаточно. Явный offset0 свежего
индекса не гарантирует совпадение с последней UI-точкой. Синхронизация как причина
недоступности прежней минуты не доказана. Следующий контролируемый шаг — явно выбрать
самый свежий supported сегмент и показывать только safe before/inside/after относительно
его интервала; никакого перебора/подгонки времени или выбора по похожему HR.
B9-14/B9-P05 и clinical gates остаются открытыми/закрытыми соответственно.
Контракт — в [Android testing](../android/docs/testing.md#обратный-контроль-sample-before-reference-band-9),
подробности — в [контексте](band9-implementation-context.md#продолжение-2026-11-18-sample-before-reference-контроль).

## Предыдущее продолжение: 2026-11-18 — граница доступного daily-окна

Для выяснения `no_preceding_file` добавлена coarse clock/horizon диагностика, не влияющая
на выбор. Один аппаратный today auto-сеанс после независимого capture установил:

- эталон не в будущем, возраст относительно телефона — `within_1h`;
- все supported aligned same-zone daily-details starts — `all_not_future`;
- все они позже эталона; **самый ранний** начинается менее чем через5минут после него,
  в тот же local day (`after_reference_gap=under_5m`, `after_reference_same_local_day=true`);
- отказ остался `selection/no_preceding_file`, ни одного fetch; HR/time/retention не PASS.

**Вывод:** выбранная точка предшествует доступному supported daily-окну. Не найдено
оснований менять endian/epoch/zone или ослаблять selector. Отсутствие этой точки во всех
видах истории и причинная связь с синхронизацией/ACK Mi Fitness **не доказаны**; ручные
измерения могут относиться к другому типу, здесь они не выбирались. Следующий контроль —
сначала bounded exact sample без предварительной синхронизации Mi Fitness, затем
независимая сверка его интервала после возврата Mi Fitness; только по отдельному запуску,
без автоперебора, raw persistence или выдачи декодированного HR за эталон.

Проверки: **109 protocol +19 architecture JVM,87 Node,4 synthetic instrumentation PASS**,
protocol lint и isolated APK PASS. Key/reference/capture request/output/lock absent,
Mi Fitness enabled/process восстановлены, task-owned ADB helper остановлен; reconnect
не проверен. Clinical gates по-прежнему закрыты. Подробности — в
[контексте](band9-implementation-context.md#продолжение-2026-11-18-граница-доступного-daily-окна).

## Предыдущее продолжение: 2026-11-18 — локализация reference selection

| Проверка | Результат | Предел доказательства |
|---|---|---|
| Safe history diagnostics | PASS | Фиксированные stage/selection без ID, timestamps, readings или исходных исключений; отказ сохраняет стадию после закрытия клиента |
| Независимый tooltip Mi Fitness | PASS в узком scope | Bounded accessibility capture уже открытой точки, две одинаковые выборки, обязательная дата; private consume/delete, без host-файла с эталоном |
| Past reference auto | BLOCKED до fetch | Для предыдущего дня `selection/no_preceding_file`; в отдельном сеансе с сегодняшним эталоном `selection/empty`. Это не доказательство удаления истории |
| Явный today reference auto | BLOCKED до fetch | После исправления today GET получен индекс, но `selection/no_preceding_file`; значения HR/steps и retention не проверялись |
| Host validation | PASS | 106 protocol +19 architecture JVM,84 Node, protocol lint и isolated APK; architecture реально перезапущена на финальных исходниках |
| Synthetic instrumentation | PASS | 4 теста на финальном APK: private reference3 + tooltip parser1; без Bluetooth/ключа |
| Cleanup | PASS в узком scope | Key/reference/capture-request/capture-output/lock absent; Mi Fitness enabled и процесс запущен; task-owned ADB helper остановлен. BT reconnect не доказан |

Добавлены отдельный `history_reference_today OFFSET/auto` и reusable safe UI capture;
нет автоматического перехода между past/today. Today GET содержит health/options, ID в
ответах обоих индексов читаются из health field2. Первоначальный today `stage=index`
исправлен, но совпадение независимой минуты **не подтверждено**. Exact-ID/CRC/padding,
лимиты и запреты ACK8/5/SET/live/import не ослаблены. B9-14/B9-P05 остаются открыты.
Следующий шаг — независимо получить покрытую индексом минуту и проверить её exact-файл,
не менять epoch/zone для совпадения и не перебирать файлы автоматически.
Подробности — в [контексте](band9-implementation-context.md#продолжение-2026-11-18-локализация-reference-selection)
и [контракте](../android/docs/testing.md#независимый-эталон-истории-band-9).

## Предыдущее продолжение: 2026-11-18 — независимый reference-point

| Проверка | Результат | Предел доказательства |
|---|---|---|
| Эталон из Mi Fitness UI | PARTIAL | Независимые minute+HR из tooltip; steps отсутствуют. Ни значений, ни дат/raw UI в отчёте/host-файлах нет |
| Reference offset0, два явных сеанса | BLOCKED для time/HR | Repeat exact bytes/aggregates и full past retention PASS; safe diagnostic: minute вне файла, timezone совпал, HR/steps=null. Не ошибка числового HR |
| Reference-time auto, один отдельный сеанс | BLOCKED | Auth/guard/battery/GET PASS, отказ history-phase до завершённой проверки; конкретная причина пока не выделена. Нет перебора/retry/fallback |
| Host validation | PASS | 97 protocol +19 architecture JVM,68 Node, protocol lint и isolated APK |
| Synthetic private reference на Samsung | PASS | 3 instrumentation-теста на финальном APK, без Bluetooth и ключа; ошибки/размер/null/нули и consume/delete |
| Cleanup | PASS в узком scope | Key/reference/lock absent, Mi Fitness enabled и процесс запущен; Bluetooth reconnect не доказан |

Добавлены internal reference comparator и явный `history_reference OFFSET/auto` с bounded
stdin/private-file и schema3 safe bool/null. Подробности — в [контексте](band9-implementation-context.md#продолжение-2026-11-18-независимая-reference-point-проверка-истории)
и [контракте эталона](../android/docs/testing.md#независимый-эталон-истории-band-9).
Это инструмент проверки, **не подтверждённые clinical time/value/full-coverage gates**.
B9-14/B9-P05 остаются открыты; HR0/255 и поминутные steps требуют независимых эталонов.
Настройки браслета/ACK8/5/live, patient/staff APK, сервер и watermark не менялись.

## Предыдущее продолжение: 2026-11-18 — wire decoder и repeat

| Проверка | Результат | Что это доказывает |
|---|---|---|
| Past exact sample, probe0.32 | PASS в узком scope | Один details v4, 2674B/166 records; exact ID/CRC/pad и full past retention unchanged. Не проверка числовых измерений/времени/полного архива |
| Kotlin library repeat, пересобранный APK | PASS в узком scope | `history_repeat 0 --pause-mi-fitness`: две передачи того же ID, byte/aggregate equality, retention unchanged, clinical import blocked; cleanup PASS |
| Today summary/full bounded read | BLOCKED | `history_file_id_mismatch`: вместо summary получен CRC-valid82B details v4 с другим ID; parser не запускался, retention не достигнут |
| Восстановление телефона | PASS для cleanup | Mi Fitness enabled-setting восстановлен, процесс запущен, ключи обоих test APK и library lock отсутствуют. BT reconnect не проверен |

После `force-stop` Mi Fitness самопроизвольно перезапускалась. С разрешения владельца
проверен opt-in pause через временный `disable-user` без очистки данных или bond;
runner теперь восстанавливает исходный default/enabled в finally и проверяет его.
Прежний Kotlin history-phase отказ в свежем repeat не воспроизвёлся — причина неизвестна.

Внутренний wire decoder v4 теперь выделяет числовые поля, сохраняя null/HR0/HR255,
но не создаёт timestamp, wear-состояние или клинические минуты. Обычный aggregate-путь
не сохраняет индивидуальные записи. **82 protocol +19 architecture JVM,54 Node,
protocol lint и instrumentation APK PASS**. Подробности и дальнейшие блокеры — в
[контексте реализации](band9-implementation-context.md#продолжение-2026-11-18-wire-decoder-и-воспроизводимый-library-repeat).
Ни SET/live/ACK8/5, ни сервер/данные пациента в этом продолжении не использовались.
Основная таблица ниже — исходный снимок; новые узкие PASS не закрывают B9-14/B9-P05.

## Стенд

| Компонент | Значение |
|---|---|
| Браслет | Xiaomi Smart Band 9, M2345B1, обычная версия, не Active/Pro |
| Прошивка | 2.3.98 — извлечена только строка `firmwareVersion` из локального лога Mi Fitness |
| Телефон | Samsung Galaxy S25 FE, SM-S731B |
| ОС | Android 17, API 37 — прочитано через ADB |
| Mi Fitness | `com.xiaomi.wearable`, 3.59.2i, versionCode 359002 |
| Транспорт | Bluetooth Classic BR/EDR; Android сообщает активный ACL BR/EDR и сервис SPP |
| Идентификация | Имя `Xiaomi Smart Band 9 2A3F`, окончание адреса `2A:3F`; полный адрес не публикуется |
| Приложения клиники | Рабочие данные не очищать и не использовать для эксперимента |

## Правила безопасности

- Не сбрасывать браслет, не отвязывать от Mi Fitness, не обновлять прошивку.
- Не выполнять `pm clear`, не удалять приложения и базы пациента.
- Не выводить ключи, QR, аккаунты, полные логи Mi Fitness и персональную историю в отчёт.
- Не отправлять команды Huami/Band 6 браслету Band 9.
- Не отправлять подтверждение удаления истории: сначала отдельно исследовать семантику
  подтверждений Xiaomi. Ограничение Band 6 про команду `03` нельзя механически переносить сюда.
- Изменение интервала пульса допустимо только после чтения исходного значения и с его
  восстановлением; сбой восстановления считается отдельным FAIL.
- Не подменять поминутные записи нулями и не выдавать итоговые шаги за историю.
- Для серверных сценариев использовать отдельный тестовый стенд и фиктивного пациента.
- Диагностический драйвер не считать интеграцией в приложение пациента или персонала.

## Статусы

**PASS** — наблюдался ожидаемый результат; **FAIL** — воспроизводимое отклонение;
**PARTIAL** — подтверждена только явно указанная часть сценария;
**BLOCKED** — проверка ещё невозможна, причина указана; **TODO** — не запускалась.
Наличие команды в Gadgetbridge само по себе не является PASS на нашем устройстве.

## Чек-лист и результаты

| ID | Проверка | Ожидаемый результат | Статус | Фактическое наблюдение / причина блокировки |
|---|---|---|---|---|
| B9-01 | USB/ADB | Телефон имеет состояние `device` | PASS | Повторно проверено в этом прогоне: доступ по USB сохраняется |
| B9-02 | Идентичность и версии | Подтверждены модель телефона, ОС, Mi Fitness и прошивка браслета | PASS | Значения стенда выше прочитаны через ADB; модель M2345B1 сообщил владелец |
| B9-03 | Bluetooth-транспорт | Bond сохранён, найден SPP | PASS | Системная диагностика Android: DUAL, активен BR/EDR, сервис SPP; BLE-протокол Band 6 не используется |
| B9-04 | Наличие ключа | Локально найден кандидат, относящийся к этому браслету, без раскрытия | PASS | В XiaomiFit.main.log найден один уникальный ненулевой кандидат в записях с моделью Xiaomi Smart Band 9; значение не выведено и не записано в репозиторий. Работоспособность проверяется отдельно в B9-06 |
| B9-05 | Изолированный SPP-сеанс | Установлено и закрыто соединение, получена версия протокола | PASS | После исправления диагностического парсера: SPP v2, version response channel=0, opcode=1, data_type=0, payload=2.0.97; transport завершён за 391 мс, socket закрыт |
| B9-06 | Авторизация Xiaomi | Проверена подлинность ответа и получено успешное подтверждение | PASS | Probe 0.3: authenticated=true, watch_hmac_verified=true; 6 отправленных / 6 полученных кадров, 950 мс. Кандидат ключа подтверждён, значение не раскрыто |
| B9-07 | Заряд и сведения устройства | Валидный заряд 0–100%, сведения согласованы с браслетом | PASS | В отдельном authenticated read-сеансе прочитаны 98%, M2345B1, 2.3.98; версия совпадает с Mi Fitness, модель — с данными владельца. Визуальная сверка заряда на экране не выполнялась |
| B9-08 | Шаги за сегодня | Значение согласовано с экраном браслета с учётом времени | BLOCKED | В v0.9 запрошена daily summary v5, но полученный CRC-valid файл имеет другой ID; не разобран, today_steps=null. Значение с экрана не сверялось. Realtime steps не подменяют суточный итог |
| B9-09 | Непрерывный пульс | Получены последовательные события с разными значениями и отметками времени | PASS | Два независимых 30-секундных сеанса: по 3 валидных показания в каждом. Первый пульс примерно через 20–22 с; первые события не содержат поля HR. Сырые показания в этот документ не включены |
| B9-10 | Остановка пульса | Поток остановлен, соединение освобождено; повторный старт успешен | BLOCKED | В обоих сеансах stop отправлен и socket закрыт, повторный запуск успешен. Однако ACK stop и независимая остановка датчика не подтверждены: `live_stop_acknowledged=false`. Полный PASS не ставится |
| B9-11 | Переподключение | Корректный повторный вход после закрытия сеанса | PASS | Auth, read и два live-сеанса отдельно установили SPP v2 и прошли проверку watch HMAC |
| B9-11N | Неверный ключ | Ключ отклоняется без порчи привязки | TODO | На личном браслете не выполнялось; отказ по HMAC покрыт host-тестами, что не заменяет аппаратную проверку |
| B9-12 | Чтение настроек пульса | Получены исходный режим и статус доступных интервалов | PASS | Probe 0.5, GET 8/10: disabled=false, interval=0 (Smart, не «выкл.»), поля присутствуют. Список доступных интервалов в публичной схеме ответа отсутствует: available_intervals=null; допустимость 1/5/10/30 мин не объявляется |
| B9-13 | Изменение интервала и восстановление | Выбрана 1 минута, проверено чтением, затем возвращено исходное значение | TODO | Только после B9-12 и проверки команды на этой прошивке |
| B9-14I | Индекс истории (без скачивания) | Прочитан список файлов за сегодня/прошлое; показаны только количества/типы | PASS | Probe 0.5: 2 дескриптора. Контрольный v0.9: 7 (6 details v4, 1 summary v5), past — 0. v0.16: 10 details v4, summary отсутствует, past — 0; 26/26 кадров, 1193 мс. В режиме index download/ACK не выполнялись. Рост списка и смена состава не объяснены; пустой past не доказывает отсутствия старой истории в Mi Fitness |
| B9-14 | История пульса и шагов | Временные метки, гранулярность, пропуски и часовой пояс корректны | PARTIAL | v0.13: первый details v4 — 98 bytes, 5 записей, HR/steps 5/5, CRC/ID/padding/retention PASS. v0.16 `history_sample_next`: следующий details — 66 bytes, 3 записи, HR/steps 3/3, CRC/ID/padding/retention PASS. Установлена гранулярность upstream 60 с; временные метки, числовые значения, часовой пояс и остальные 8 сегментов ещё не проверены; summary v5 отсутствует в текущем индексе из 10 details |
| B9-15 | Повторная выгрузка | Нет дублей и потери истории для Mi Fitness | PARTIAL | v0.15 same-session repeat: один и тот же первый ID запрошен дважды, оба файла 98 bytes, агрегаты и внутренний SHA-256 совпали, retention index unchanged. Это не проверка повторной выгрузки всех сегментов и не доказательство сохранности всей истории; ACK 8/5 не отправлялся |
| B9-16 | Снят / зарядка / сон | Коды Xiaomi сопоставлены с нашей моделью по наблюдениям | TODO | Не использовать числовые коды Band 6 без проверки |
| B9-17 | Привязка пациента и выдача персоналом | Модель распознана; ключ хранится защищённо; QR работает | BLOCKED | Текущие приложения используют драйвер и поиск Band 6 |
| B9-18 | Фоновая синхронизация | Новые данные поступают после блокировки экрана и закрытия UI | BLOCKED | Нужна интеграция адаптера Band 9 и тестовый пациент |
| B9-19 | Телефон → сервер → панель | История и пульс доставлены; повторная отправка идемпотентна | BLOCKED | Нет интеграции и отдельного тестового стенда для Band 9 |
| B9-20 | Запрос врача и оповещения | Запрос запускает измерение; остановка и отчёт работают | BLOCKED | Зависит от B9-09, B9-10 и B9-19 |
| B9-21 | Возврат к обычному использованию | Mi Fitness снова подключается, bond сохранён, временный ключ удалён | PASS | Ранее подтверждён зашифрованный BR/EDR ACL после возврата к Mi Fitness. После каждого нового сеанса v0.7–0.9 подтверждены отсутствие auth-key и работающий Mi Fitness; каждый probe видит bonded=true. Контрольный GET сохранил enabled/Smart, SET/ACK 8/5 не отправлялись. Свежая независимая проверка ACL и визуальная сверка владельцем после history_read не выполнены |

## Источники

Проверены 2026-11-12:

- [Характеристики Xiaomi](https://www.mi.com/global/product/xiaomi-smart-band-9/specs/): Mi Fitness, Android 8.0+.
- [Документация Gadgetbridge: Band 9](https://codeberg.org/Freeyourgadget/website/src/branch/main/docs/gadgets/wearables/xiaomi.md#mi-band-9): экспериментальная поддержка, ограничения.
- [Xiaomi protobuf](https://codeberg.org/Freeyourgadget/website/src/branch/main/docs/basics/topics/xiaomi-protobuf.md): авторизация, заряд, realtime, настройки пульса, экспериментальная история.
- [Получение ключа](https://codeberg.org/Freeyourgadget/website/src/branch/main/docs/basics/pairing/huami-xiaomi-server.md): локальные логи Mi Fitness, ограничения версий приложения, запрет отвязки/сброса.
- [MiBand9Coordinator](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/xiaomi/watches/MiBand9Coordinator.java): `BT_CLASSIC`.
- [XiaomiSppSupport](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppSupport.java): SPP и согласование версии транспорта.

Связанные документы: [общая ручная приёмка](manual-acceptance-checklist.md),
[протокол Band 6](../android/docs/ble-protocol.md).

## Журнал прогона

- 2026-11-12: сохранены исходные наблюдения ADB. Аппаратные проверки команд Xiaomi ещё не выполнялись.
- Повторная проверка ADB: PASS. В локальном логе Mi Fitness найден единственный кандидат
  ключа, связанный с моделью Band 9. Подтверждения авторизации пока нет.
- Создана утилита [безопасной подготовки ключа](../tools/band9-prepare-key.mjs):
  читает лог в памяти, отказывается при неоднозначности и не печатает секрет. В режиме
  `stage` передаёт кандидат через stdin в закрытое хранилище диагностического приложения.
  Проверка `node --test tools/band9-prepare-key.test.mjs`: 3 теста PASS; проверка
  `check` на настоящем телефоне: PASS. Полные исходные логи на диск компьютера не сохранялись.
- Создан и установлен отдельный [Band9 Probe](../tools/band9-probe/README.md), пакет
  `ru.kardiomonitor.band9probe`. Сборка, 26 host-тестов и lint без ошибок прошли.
  Наличие APK и тестов не означает поддержку устройства приложением пациента.
- Первая попытка transport отменена из-за блокировки экрана до обмена с браслетом.
  После разблокировки RFCOMM/SPP соединение установилось: 1 переданный / 1 полученный кадр,
  795 мс; получено `version_response_invalid`. До авторизации не дошло.
  Требуется уточнение диагностического парсера; это не доказательство неисправности браслета.
- После обеих попыток сокет закрыт, Mi Fitness открыт обратно. В системной диагностике
  снова наблюдается активный зашифрованный BR/EDR ACL к Band 9; bond сохранён.
  Настройки пульса, история и данные установленного KardioMonitor не затрагивались.
- Probe 0.2: transport PASS, получен SPP v2 / 2.0.97. Следующая попытка auth
  остановилась на локальной проверке `v2_session_version`, до передачи auth protobuf.
  Удалено необоснованное равенство версии session config версии запроса, с сохранением
  проверок длины TLV и границ; поведение сверено с upstream.
- Probe 0.3: auth PASS, watch HMAC проверен; read PASS для заряда (98%), модели
  M2345B1 и прошивки 2.3.98. Общий статус режима read — partial, потому что суточные
  шаги не запрашиваются.
- Первый live-сеанс 10 с получил два realtime-сообщения без поля пульса. Это не PASS
  для HR. В Probe 0.4 окно увеличено до 30 с; отсутствие валидного HR теперь явно
  даёт partial, а не маскируется наличием шагов.
- Два последовательных live-сеанса Probe 0.4: PASS для получения HR, по 3 валидных
  показания, время до первого HR около 20 и 22 с. Полная длительность 30586 и 30904 мс,
  соответственно 22/21 и 23/22 переданных/полученных кадров. В каждом отправлен stop,
  сокет закрыт. ACK stop не получен/не ожидается текущим probe — остановка датчика
  отдельно не доказана.
- Итоговая проверка: 30 векторных/синтетических protocol-тестов PASS, 3 теста
  безопасного извлечения ключа PASS, assembleDebug/lintDebug PASS (0 lint errors),
  подпись APK проверена. SHA-256 Probe 0.4:
  `ff1139ad4ea4f09ff25873046059c37caf1ab7c33e357d72e438edcfcb33454c`.
- Завершение: временный `files/auth-key` удалён из probe, отсутствие подтверждено;
  Mi Fitness запущен обратно, активный зашифрованный BR/EDR ACL и прежний bond
  подтверждены. Приложение KardioMonitor осталось установленным, его данные не очищались.

## Исследование безопасности истории — второй этап

Проверены исходники Gadgetbridge: [XiaomiHealthService](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiHealthService.java),
[XiaomiActivityFileFetcher](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileFetcher.java),
[XiaomiPreferences](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiPreferences.java).

- Константы реализации: `8/1` — индекс сегодня, `8/2` — индекс прошлого,
  `8/3` — запрос файла, `8/5` — подтверждение истории. Комментарий в
  `wear_fitness.proto` о номерах today/past не совпадает с реализацией; не использовать его
  как источник номеров команд.
- Список содержит 7-байтовые идентификаторы файлов, а не поминутные записи:
  timestamp u32 LE, timezone i8 (четверти часа), version u8, flags u8.
- Скачивание выполняется отдельным запросом `8/3`. Fetcher проверяет CRC32 полного файла,
  затем отправляет `8/5` только при выключенном `keep_activity_data_on_device`.
  Этот ACK отличается от транспортных ACK SPP, необходимых для передачи кадров.
- В Gadgetbridge существует режим сохранения активности на устройстве, который пропускает
  ACK истории. Это основание отделить чтение от подтверждения, но не аппаратное доказательство
  сохранности всех файлов на каждой прошивке.
- В Probe 0.5 разрешены только списки индекса. Скачивание файлов и ACK истории
  не запускались. Индексы/временные метки пользователя не публикуются; в отчёт идут только
  количества, типы и версии форматов. Шаги и пульс по индексам не восстанавливаются.
  Для следующего режима отдельно готовится ограниченное чтение двух обнаруженных файлов
  сегодняшней daily-истории без ACK; неизвестные форматы не интерпретируются.
- [DailyDetailsParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/impl/DailyDetailsParser.java)
  поддерживает форматы 1–4 и интерпретирует одну запись как минуту; поля шагов и HR
  опциональны и управляются битовой картой. Это факт реализации upstream, а не проверка
  нашего Band 9. Нельзя переносить восьмибайтовый формат Band 6 или превращать отсутствие HR
  в нулевое измерение. Декодирование файлов и проверка часового пояса ещё не выполнены.
- Подготовлен [read-only runner](../tools/band9-run-readonly.mjs) только для `settings`
  и `history_index`: перед запуском проверяет блокировку экрана и отсутствие активного
  probe; временный ключ удаляет в finally, Mi Fitness открывает обратно. Не запускает
  live, запись настроек или скачивание истории. С утилитой извлечения ключа проверено
  9 host-тестов, включая отказ/таймаут/очистку. Это не заменяет аппаратный запуск.

## Аппаратный прогон второго этапа (Probe 0.5)

- `settings`: PASS, 1144 мс, 15 переданных / 15 полученных кадров. Конфигурация прочитана,
  не изменена. Пульс включён, режим Smart. Заряд 97%, модель и прошивка совпали.
- `history_index`: PASS, 980 мс, 18/18 кадров. Today: daily details version 4 и summary
  version 5, по одному файлу. Past: пустой список; не трактуется как отсутствие истории
  в официальном приложении. Download/ACK history не выполнялись.
- После каждого сеанса runner подтвердил отсутствие private `auth-key` и работающий
  процесс Mi Fitness. Свежая авторизация и watch HMAC успешно проверены в каждом сеансе.
- Сборка 0.5: 46 protocol host-тестов, assembleDebug и lintDebug без ошибок; отдельно
  9 тестов Node-утилит. Аппаратные результаты не подменяются этими тестами.

## Аппаратное чтение истории (Probe 0.6–0.16)

- v0.6: `history_read_ambiguous_daily_index` до скачивания. Today уже содержал
  два details v4 и одну summary v5 вместо исходной пары; один файл на вид —
  слишком жёсткое предположение диагностики.
- v0.7–0.9: допускается до восьми уникальных разрешённых today-файлов; идентичные
  ID дедуплицируются. Две разные сводки или превышение восьми файлов вызывают отказ
  **до скачивания**; нет усечения/выбора наугад. Неизвестные виды не скачиваются.
  Бюджеты не увеличены: 512 KiB суммарно, абсолютные 75 секунд, 30 секунд на файл.
  С v0.9 единственная сводка запрашивается первой, details — в порядке индекса.
- v0.7: первый details-файл, 98 байт / один chunk, `crc_valid=true`,
  `file_id_match=true`; парсер вернул `unsupported_details_header`, никаких
  поминутных данных не заявлено. Следующий файл — CRC корректна, ID не совпал,
  безопасный abort. В индексе выбрано пять файлов; полного получения нет.
- v0.8: несовпадение воспроизведено уже на первом файле. Добавлены только булевы
  признаки сравнения частей ID: timestamp не совпал, timezone/version/category
  совпали. Исходные ID, даты и payload не раскрываются.
- v0.9: выбрано шесть файлов, первым запрошена summary v5. Получено 82 байта,
  один chunk, CRC корректна, но все части ID отличаются; файл **не считается
  запрошенной сводкой**, не разбирается. Protobuf receipt 8/3 до завершения файла
  не наблюдался; отсутствие receipt само по себе не доказывает ошибку протокола.
  Итог `history_file_id_mismatch`, 1109 мс, socket закрыт. Причина пока не установлена;
  проверка ID не ослаблялась и неизвестный формат не угадывался.
- Во всех download-попытках `history_file_ack_attempted=false`,
  `raw_history_persisted=false`; повторный retention index внутри того же сеанса
  **не достигнут**. Нельзя утверждать ни сохранность всей истории, ни отсутствие
  дублей; рост количества дескрипторов также не объяснён.
- Контрольные v0.9: GET settings PASS, 1083 мс, 15/15 кадров, `disabled=false`,
  `interval=0` (Smart); GET index PASS, 647 мс, 18/18 кадров, today=7 дескрипторов
  (6 details v4, 1 summary v5), past=0. По количеству нельзя подтвердить сохранение
  тех же ID. Ни SET 8/11, ни history ACK 8/5 не отправлялись.
- После каждого запуска runner удалил ключ и восстановил процесс Mi Fitness;
  отдельная финальная проверка отсутствия ключа/наличия процесса также PASS.
  Сброс/отвязка, production-приложения, пациентские данные и сервер не затрагивались.
- v0.13: исправление по upstream-парсеру: последний зарезервированный nibble
  заголовка details v4 не является двенадцатой группой и не должен отвергать файл.
  Host-проверка прогнала все 16 значений nibble; валидный fixture разбирается.
  На Band 9 после этого получен details v4: 98 bytes, 5 records, HR/steps
  presence 5/5, CRC/ID/padding PASS, retention index unchanged. Безопасный sample
  пропустил 9 из 10 разрешённых кандидатов.
- v0.15: режим `history_repeat` в одном SPP-сеансе дважды запросил тот же первый
  details ID. Оба файла: 98 bytes, 5 records, HR/steps presence 5/5; aggregate
  projection и внутренний digest совпали, retention index unchanged. Сырые байты и
  digest не попали в отчёт; history ACK 8/5 не отправлялся. Это подтверждает
  повторяемость одного выбранного сегмента, не всей истории.
- v0.16: добавлен отдельный `history_sample_next`, который выбирает только второй
  уникальный details ID в порядке индекса, не выбирая summary. На текущем индексе
  из 10 details получен следующий сегмент: 66 bytes, 3 records, HR/steps 3/3,
  CRC/ID/padding PASS, retention index unchanged. Обычный `history_read` на том же
  индексе корректно отказался до скачивания с `history_read_file_count_limit`:
  лимит 8 не ослаблялся и файлы не усекались наугад.
- v0.16: **77 Java protocol tests + 14 Node tests PASS**, assembleDebug и lintDebug
  успешны. Тесты включают reserved-nibble regression, bounded sample, next-details
  selection, past-index metadata-only gate, repeat digest и отказ при изменении
  повторного файла. Это не заменяет полную проверку истории Band 9.

### Probe 0.17: явные bounded-порции — аппаратная блокировка

- Добавлен `history_batch`: обязательные typed `detail_offset` 0–255 / `detail_count`
  1–2, dedup unique details, summary исключена. Не более двух файлов за сеанс,
  без автопродолжения; прежние лимиты не подняты. Invalid/out-of-range offset не
  вызывает скачивания; неполный хвост отмечается отдельно. Offset относится только
  к свежему порядку индекса, не к долговечному cursor/полному покрытию.
- **84 Java protocol tests + 26 Node safety tests PASS**; assembleDebug и lintDebug PASS.
  Scripted SPP peer подтверждает разные последовательные ID и отказ при ошибке
  второго ID/padding/CRC; сохранена совместимость next-details. Проверено изменение
  retention, пустой/большой индекс, хвост и неверные диапазоны. Runner не принимает
  stale schema/version/range, проверяет production lock, не печатает raw IDs,
  digest, числовую историю или неизвестные поля.
- На телефоне установлено только обновление диагностического APK, без очистки данных.
  Четыре неправильных Intent (нет range / неверный тип / отрицательный offset /
  extras у settings) дали `invalid_history_range`, `hardware_attempted:false`;
  временный ключ отсутствовал. Это PASS для входного guard, не чтения истории.
- Аппаратный `history_batch 2 2`: auth/exact guard PASS, но
  `history_sample_offset_out_of_range`; `history_file_download_attempted:false`.
  Ранее наблюдавшийся today-индекс из 10 details больше не доступен по тем же позициям.
- Отдельный `history_index`: today содержит **1 details v4 + 1 summary v5**, past
  содержит **10 details v4** (раньше past был пуст). Это только counts/categories;
  перенос конкретных ID, время и причины смены индексов не доказаны. Нельзя считать
  прежний offset сохранённым или объявлять архив проверенным.
- Аппаратный `history_batch 0 2`: выбран один details из двух кандидатов, получено
  98 bytes / 1 chunk; CRC и padding корректны, но **ID не совпал**: timestamp=false,
  timezone/version/kind=true; received ID отсутствует в исходном today-индексе.
  Результат `history_file_id_mismatch`, parser=`not_parsed`, retention не достигнут.
  Ни записи HR/steps, ни время этого файла не интерпретировались. Новые поля раннего
  detail-coverage затем перенесены до download, чтобы сохраняться и при таком отказе;
  это уточнение проверено host-тестом, повторного аппаратного fetch ради него не было.
- Повторно сверены pinned wire-факты `XiaomiHealthService`/`wear_fitness.proto`:
  запрос 8/3 содержит Health.field2 с 7-byte ID. Исправления запроса наугад,
  смягчения exact-ID или перебора иных команд не делались. Причина несовпадения
  остаётся **BLOCKED**, не объясняется одной лишь корректной CRC.
- Во всех трёх сеансах socket закрыт, временный ключ удалён, процесс Mi Fitness
  возвращён; реальное Bluetooth-переподключение не доказано. ACK 8/5, SET и live
  не отправлялись, сырая история не сохранялась. Приложения клиники/сервер/данные
  пациентов не затрагивались.

**Дальше:** локализовать источник CRC-valid файла с несовпадающим ID, не ослабляя
проверки. Отдельно подготовить безопасный bounded past-read с точным сопоставлением
ID (нынешний batch намеренно today-only), затем доказать время/значения и summary v5.
До этого B9-08/B9-P05 и полная production-интеграция не пройдены.

### 2026-11-13: происхождение файла и архивный bounded read (probe 0.18–0.20)

- **Реализовано**: wipeable snapshot обоих индексов в sample-сеансе; `past_index_*`
  membership и today membership — только bool/null, без экспорта ID/времени/числовой
  истории. Сопоставление разрешено только при CRC. Missing field не становится
  пустым индексом. Ошибка/закрытие очищает снимки; parser по-прежнему требует exact ID.
- **Реализовано**: явный `history_past_batch OFFSET COUNT` с теми же лимитами 0–255/1–2
  и общим бюджетом 512KiB/75s. Последовательность после identity: `8/2 → 8/1 → до двух
  8/3 → 8/2`; выбираются только past details, summary не скачивается. Retention — полный
  multiset выбранного past-индекса, не today и не всех типов истории. Ни auto-cursor,
  ни автоматического архивного обхода, ни ACK/SET/live не добавлено.
- v0.18 today batch0/1: candidates5 (4details+summary), past10. Получено98B, CRC/padding
  PASS, timestamp ID mismatch; received ID отсутствует и в today, и в past snapshot.
  Гипотеза «вернулся один из файлов past вместо today» этим **не подтверждена**.
- v0.19 past batch0/1: candidates10details, selected1/omitted9. Получено82B, CRC/padding
  PASS, timestamp ID mismatch; received ID отсутствует в обоих снимках. Следовательно,
  смена источника запроса с today на past проблему не устранила.
- v0.20 past batch0/1: снова CRC-valid98B, timestamp mismatch, timezone/version/kind=true;
  membership today/past=false. Добавленная enum-only классификация временных четырёх
  байтов вернула `unclassified`: byte order reversal, ±signed descriptor timezone×900
  и same-minute не объясняют несовпадение. Сами timestamps/deltas не выводились.
  Это не доказательство семантики времени измерений и не оправдание пересчёта ID.
- Во всех трёх аппаратных попытках parser=`not_parsed`, retention index не достигнут;
  **архивное чтение аппаратно не прошло**. Никаких raw dumps, numerical imports,
  ACK8/5, SET, live, сброса/отвязки. Временный ключ удалён и socket закрыт; процесс
  Mi Fitness восстановлен, её Bluetooth reconnect не подтверждён.
- Итоговый host regression: **93 Java protocol tests +28 Node safety tests PASS**;
  assembleDebug/lintDebug PASS. Synthetic peer отдельно доказывает exact past requests,
  два последовательных файла и past retention/его изменение, fail-closed даже при
  ID из другого индекса; tests absent/malformed past, CRC-null membership, wiped ID и
  арифметические timestamp relations. Это не заменяет аппаратную приёмку.
- Повторно изучены pinned `XiaomiActivityFileId` и `XiaomiActivityFileFetcher`: дескриптор
  round-trips u32LE + timezone/version/flags; upstream fetcher использует заголовок
  полученного файла без доказательства exact requested ID. Его более слабое принятие
  здесь не перенесено. Исходники не vendored/не копировались.

**BLOCKED**: причина выдачи CRC-valid файла вне обоих индексов. Возможный delayed/queued
bulk — только гипотеза; далее нужна корреляция 8/3 и bulk без подавления ошибок или ACK8/5.
На этом этапе новые запросы ради перебора остановлены. B9-P05 остаётся BLOCKED;
полная история, время/значения, summary/today steps и клиническая синхронизация не готовы.

### 2026-11-13: один fetch и два входящих заголовка (probe 0.21–0.22)

- Добавлен явный `history_correlate OFFSET 1`: только один past details target,
  ровно один 8/3, не более двух файлов после команды, остановка на первом exact ID.
  CRC/padding проверяются, но оба файла остаются `parser:not_parsed`; никакого
  числового импорта, retention, повтора fetch или ACK8/5. Верхний статус всегда partial.
- **Аппаратный v0.21/schema9**: selected1 из10 past details; до запроса во время
  ожидания индексов замечено10 bulk-кадров. После одного8/3 первый файл34B — CRC/pad
  PASS, ID mismatch, membership today/past=false; transport ACK fetch ещё не замечен.
  Второй1394B — CRC/pad PASS, exact requested ID, past=true/today=false; transport ACK
  уже замечен. Protobuf receipt8/3 не замечен. Код `diagnostic_exact_after_unmatched`,
  1428B, 1236ms. Сырые ID/байты/измерения не выводились и не сохранялись.
- Это подтверждает наличие незапрошенного потока в сеансе: нельзя считать первый
  complete bulk ответом на8/3. **Не доказаны** источник/причина этого потока, безопасный
  production demux, время/значения, полнота покрытия и сохранность полного архива.
  Прежние ID-mismatch отказы были защитными; отключать exact-ID или чистить поток8/5 нельзя.
- **v0.22/schema10** после аппаратного наблюдения: не более32 пропущенных encrypted
  bulk-кадров при ожидании protobuf; общий512KiB для пропущенного и обрабатываемого
  channel5 payload вместе с chunk headers, без повторного учёта transport duplicate.
  Plaintext/flood/budget abort; один30s stage на всё correlation, абсолютный75s.
  Новые ограничения проверены synthetic peer, не аппаратным повторным fetch.
  Runner не принимает прежний v0.21/schema9 как результат текущей сборки.
- **99 Java protocol +30 Node safety tests PASS**, assembleDebug/lintDebug PASS.
  Проверены exact-first/mismatch-then-exact/two-mismatch, prefetch flood/bytes/plaintext,
  совокупный prefetch+fetch budget, CRC/padding/channel, late receipt, EOF, timeout и
  cancellation с сохранением безопасного partial evidence; один fetch и отсутствие8/5.
- После аппаратного v0.21 socket закрыт, временный ключ удалён, процесс Mi Fitness
  восстановлен; Bluetooth reconnect не подтверждён. SET/live/reset/unpair не было.
  Клинические приложения, сервер и данные пациентов не затрагивались.

**Следующий барьер:** bounded demux/quarantine, допускающий раздельное наблюдение
незапрошенных файлов без их принятия/импорта, затем проверка exact target и retention.
B9-P05 остаётся BLOCKED: diagnostic exact header не означает готовую полную историю.

### 2026-11-13: target-only demux и quarantine (probe 0.23/schema11)

- Реализован отдельный `history_target OFFSET 1`, не изменение поведения прежних
  read/batch. После auth/exact identity: **8/2 → 8/1 metadata → один8/3 → 8/2 retention**.
  Только один past details target из свежего индекса. Не более двух завершений
  входящих файлов после команды до exact target, без повторного fetch.
- Quarantine работает ещё во время ожидания индексов и при retention: один assembler,
  строгий chunk order, CRC/pad, неизвестная категория допустима лишь как header-only.
  Файл, начатый до8/3, не становится requested даже после команды/при совпадении ID.
  Плохие CRC/pad/order/plaintext/channel — отказ, не пропуск до «хорошего» файла.
  Буферы/ID очищаются в finally, в том числе при сбое observer.
- Только новый после команды exact-ID файл допускается к существующему aggregate
  parser (record/presence counts, без numerical HR/steps/time). Полный past multiset
  сравнивается после target; изменившийся index и unsupported layout не дают verified
  sample. Trailing incomplete quarantine на момент retention response — отказ.
- Общие bounds:512KiB всех channel5 payload с chunk headers,32 bulkframes суммарно
  во время protobuf waits,16 complete files/session,один30s target stage/absolute75s;
  прежние chunk/frame limits. Transport duplicates ACKed, не повторно counted/parsed.
  Два mismatch приводят к отказу без чтения третьего файла, retry или8/5.
- **113 Java protocol +32 Node safety tests PASS**, assembleDebug/lintDebug PASS.
  Проверены pre-index exact/unknown/partial и продолжение после8/3, mismatch→exact,
  два mismatch, duplicates, CRC/pad/order/channel/plaintext, общий byte budget,
  changed retention, trailing partial/corrupt files, EOF/timeout/cancel, wipe включая
  observer failure и строгие CLI/Intent/version/schema gates. Отчёт с routes/phase/
  ordinal ограничен16 safe summaries, без raw ID/измерений/даты.
- Начальный preflight screen lock остановлен без key/BT. Пользователь затем
  разблокировал телефон и согласовал один read-only прогон; повторный preflight PASS.
  Установлено только обновление isolated probe0.23, без clear/приложений пациентов.
- **Аппаратный `history_target 0 1`: FAIL для target read, PASS fail-closed.**
  Auth/exact identity PASS, selected1 из10 past details. Во время index wait собраны
  полностью10 CRC/pad-valid файлов868B суммарно, quarantined без parser. После одного8/3
  пришли418B и82B, оба CRC/pad-valid, exactID=false/membership today=false/past=false.
  Достигнут лимит двух завершений: `history_target_not_received`, elapsed928ms,
  все12 parser=`not_parsed`; retention не запрашивался, retry/третий файл не читался.
  В отличие от v0.21, exact target в двухфайловом окне не наблюдался; это не основание
  повышать лимит/принимать receivedID или очищать поток8/5.
- Добавлен synthetic regression формы10 prefetch +2 mismatch (фиктивные байты/ID,
  не реальные данные): ошибка и отсутствие чтения queued третьего target/retention/8/5.
  Третий реальный файл и наличие target дальше по потоку **неизвестны**.
- Socket закрыт, временный ключ удалён, Mi Fitness process восстановлен; финальные
  probe+production keys/lock отсутствуют. Bluetooth reconnect не доказан. ACK8/5,
  SET/live/reset/unpair/raw persistence не было, данные пациентов не затрагивались.

**BLOCKED:** exact target в bounded окне не получен, retention на железе не достигнут.
До новых hardware fetch определить правила маршрутизации/границы unsolicited потока;
не повышать окно/повторять запросы наугад. Даже будущий `verified_target_sample` — partial,
не подтверждение полной истории, времени/значений или production driver. B9-P05 BLOCKED.

### 2026-11-13: index-only границы потока (probe 0.24/schema12)

- Новый `history_boundary` без range: после auth/exact identity только **8/2 → 8/1**,
  без8/3. Отдельный send-gate разрешает только index subtypes1/2. Header-only observer
  сохраняет stage/subtype первого/последнего bulk, transportACK-seen текущего index,
  counts и deferred membership после обоих ответов. Payload wiped сразу; до16 private
  CRC/pad-validated ID по7bytes остаются лишь до сравнения и wiped вместе со snapshots
  в finally (включая observer failure). Неизвестные/недоступные membership=null.
- Прежние bounds512KiB/32bulkframes/16files/absolute75s; index stages6s. CRC/pad/order/
  channel/plaintext abort. Нет parser/import/ретраев/drain-until-idle/8/5. Завершение на
  втором index response не доказывает отсутствие последующих файлов; partial хвост
  даёт diagnostic incomplete, не quiet/full-history PASS. Target-window2 не изменено.
- **Аппаратный один `history_boundary`: diagnostic partial**,1088ms. Auth/exact
  identity PASS; fetch_command_count=0, download_attempted=false, оба index response
  получены после transportACK. Во время today-stage получены10 файлов/10bulkframes:
  868B file data,908B с chunk headers, CRC/pad PASS у всех, membership today/past=false
  у всех, parser=`not_parsed`. Семь файлов622B — до transportACK today, три246B — после.
  Partial хвоста в момент второго response нет; дальнейший поток не исследовался.
- Это подтверждает unsolicited поток **без8/3**. TransportACK не является надёжной
  границей ответа: post-ACK файлы также чужие. Не доказано, кто/какая команда создала
  поток и был ли он queued до отправки index. Сдвиг начала наблюдения к ACK проблему
  не решает; расширение target-window/новый target fetch не выполнялись.
- **121 Java +34 Node safety tests PASS**, assembleDebug/lintDebug PASS. Synthetic
  regression воспроизводит лишь форму10files,7preACK/3postACK,908B и false membership
  с фиктивными ID/байтами. Отдельно cross-index partial, missing/malformed index,
  nullable membership, duplicates, malformed/flood, EOF/timeout/cancel, wipe/observer
  failure. Guard против чтения ID после close добавлен после hardware и проверен
  host; аппаратное наблюдение ради него не повторялось.
- Публичная pinned проверка (Gadgetbridge a047f5a1fe3067f3c685dca394b734bc11b79248):
  [HealthService1015–1096](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiHealthService.java#L1015)
  подтверждает формы index/request, но upstream после index запускает собственную
  client-side fetch queue; это не объяснение поведения браслета без8/3. В изученном
  [chunk header](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileFetcher.java#L93)
  только total/number, не request token. Upstream reset/dumps/ACK/более слабое принятие
  не перенесены; исходники не копировались.
- Socket закрыт; runner cleanup и финальная проверка keys/lock absent PASS, MiFitness
  process восстановлен, Bluetooth reconnect не доказан. Нет8/3/8/5/SET/live/reset/unpair,
  raw persistence, клинических приложений/серверных изменений/данных пациентов.

**BLOCKED:** источник потока неизвестен, exact-target/retention остаётся непроверенным
на hardware; full time/value/coverage не готово. Следующий безопасный контроль должен
отличать поток уже после auth/identity до index от потока после index, не трактуя
наблюдаемый порядок как причинность. Не очищать очередь ACK8/5 и не повышать окно наугад.

### 2026-11-13: пассивное окно до index (probe0.25/schema13)

- Реализован `history_passive` без range: auth/battery/exact identity/SPPv2 → одно
  абсолютное6s окно, **ноль application commands в окне**. Transport ACK необходим
  для входящих кадров и не является history ACK. Нет8/1/8/2/8/3/8/5/SET/live/time.
- Session передаёт один monotonic deadline в Activity; traffic его не продлевает,
  общий75s watchdog сохранён. Ранний timeout, EOF/cancel и malformed/flood — abort,
  не завершённое окно. Timeout на deadline не удостоверяет завершённый transport
  frame: `transport_boundary_at_deadline:not_verified`, `absence_of_stream_proven:false`.
  Partial SPP header/payload может не попасть в file assembler и complete-frame counts.
- Header-only quarantine512KiB/32bulkframes/16files без parser и private ID copies;
  subtype/ACK/membership=null, phase=`pre_index_passive`. Wipe при любой ошибке/observer
  failure; budgets/target-window2/production gates не ослаблены.
- **Один аппаратный прогон:** auth/HMAC/exact M2345B1/2.3.98 PASS, окно6000ms истекло,
  общий сеанс6909ms; status partial, `diagnostic_passive_window_elapsed`. Наблюдалось
  **0 complete bulk frames/0 bytes/0 complete files**, начатого file assembler нет.
  History commands/index responses/fetch=0; download/ACK/retention=false, no raw history.
- Это контроль только одного bounded окна в отдельном сеансе. Предыдущие0.24 файлы
  безfetch и пересечение transportACK не отменяются. Index causality/источник потока,
  отсутствие более поздних данных или eventual exact target **не доказаны**. Не
  повторяли target/index запросы, не увеличивали окно, не посылали8/5.
- **128 Java +36 Node safety tests PASS**, assembleDebug/lintDebug PASS; editor errors
  не обнаружены. Synthetic silence/files/unknown/control/duplicates, partial file и
  partial SPP header/payload на timeout, early timeout/EOF/cancel, malformed/flood/
  budgets, no-application-send gate, no ID copies, observer failure/wipe, identity guards.
- Только изолированный probe APK обновлён через install-r; socket закрыт, финально
  оба временных ключа и production lock отсутствуют, MiFitness process восстановлен.
  Bluetooth reconnect не проверен, данные пациентов/приложения/сервер не затронуты.

**BLOCKED:** source/routing, hardware exact-target/retention и full time/value/coverage;
B9-P05/P06/P08 не закрыты. Следующее исследование должно изолировать конкретный index
и post-index окно, сохраняя отсутствиеfetch/ACK8/5 и не выводя причинность из одной
последовательности кадров. До подтверждения правила production history не включать.

### 2026-11-13: один past8/2 и post-response окно (probe0.26/schema14)

- Добавлен `history_past_window` без range. После auth/battery/exact identity/SPPv2
  **ровно один8/2**, no8/1/8/3/8/5/SET/live/time. Одноразовый send-gate запрещает
  любые дополнительные application commands. Transport ACK входящих кадров сохранён.
- Один deadline6000ms начинается до8/2 и передаётся Activity под общим75s cap;
  получение index и последующий traffic не продлевают его. Наблюдение продолжается
  после ответа до deadline, не до idle. Без валидного единственного past response
  успех не возвращается; missing/invalid/wrong/duplicate response → error. Ноль ID
  при присутствующем поле не подменяет отсутствующее поле/ответ.
- Header-only `HistoryBoundary` сохраняет прежние512KiB/32bulkframes/16files, строгие
  channel/encryption/CRC/pad/order и wipe ID/snapshot/payload при ошибках/observer failure.
  Добавлены first/last bulk phase и counts до/после index response; last metadata
  относится к последнему реальному chunk, а не stage при close. Только past membership
  вычисляется; today не запрашивался, membership=null. Нет parser/import/retention.
- **Один аппаратный прогон0.26:** auth/HMAC/exact identity PASS,6000ms окно/6953ms сеанс,
  `diagnostic_past_window_elapsed`, root partial. Index response1 после transportACK,
  history commands1 (8/2), fetch0; **10complete files/10bulkframes,868B filedata/908B
  с chunkheaders, все после past response и ACK**. До response bulkframes0.
  Все CRC/pad valid, все IDs вне past snapshot, today membership=null, все not_parsed.
  Нет partial file assembler; download/ACK/retention=false. Transport boundary на
  cutoff=`not_verified`, отсутствие потока не доказано.
- Вывод: today8/1/fetch8/3 **не нужны для этого наблюдения**, а post-response граница
  также не обеспечивает корреляцию с индексом/target. Не доказаны causal trigger8/2,
  источник/время формирования, совпадение файлов с0.24 или отсутствие поздних данных.
  Равные count/bytes не означают одинаковые ID/содержимое. Новый fetch не выполнялся,
  target-window2 не увеличивался, очередь ACK8/5 не очищалась.
- **135 Java +38 Node tests PASS**, assemble/lint PASS; editor errors не обнаружены.
  Synthetic before/after/spanning, ACK-order/duplicates, missing/invalid/wrong index,
  nullable membership, one-send gate/failed observer/wipe, partialfile/SPP header/
  payload, early timeout/EOF/cancel, malformed/flood/budgets/identity guards.
  После hardware добавлена регрессия формы10post-responsefiles/908B/outside snapshot
  с фиктивными ID/байтами; аппаратный сеанс ради теста не повторяли.
- Обновлён только isolated probe, socket закрыт, оба временных ключа и production
  lock отсутствуют, MiFitness process восстановлен. BT reconnect не доказан;
  production приложения/данные пациентов/сервер не менялись.

**BLOCKED:** безопасное правило source/routing всё ещё неизвестно; включать parser
для любого post-response файла нельзя. Hardware exact-target/retention и full time/
value/coverage остаются непроверенными. Дальше нужны независимо подтверждённые правила
идентификации потока/ID; не blind fetch retries, не увеличение окна и не8/5.

### 2026-11-13: wire ID и lifetime guards (probe0.27/schema15, host-only)

- **Новых аппаратных запросов и установки APK не было.** Сборка0.27/schema15 отличается
  от hardware0.26; runner отвергает прежнюю пару. Новый режим/команды не добавлены,
  exact-ID/parser/production gates не ослаблены. Последний hardware итог остаётся0.26.
- Независимо сверены pinned wire-факты a047f5a1fe3067f3c685dca394b734bc11b79248:
  [Command schema](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/GBProtobufGenerator/src/main/proto/xiaomi.proto)
  health10; [Health schema](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/GBProtobufGenerator/src/main/proto/xiaomi/wear_fitness.proto)
  ID bytes2 (не repeated message). HealthService handler последовательно разбирает
  по7bytes без count/record-prefix. Комментарии schema о номерах команд не считаются
  авторитетнее исполняемых constants/handler/send.
- [ActivityFileId](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileId.java#L94)
  подтверждает4timestamp bytesLE/byte timezone/version/flags; [FileFetcher](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/a047f5a1fe3067f3c685dca394b734bc11b79248/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/activity/XiaomiActivityFileFetcher.java#L93)
  подтверждает chunkprefix4, fileID bytes0..6/pad7/CRC32LEtail4. Оснований менять offset,
  byte order или принимать несовпадающий ID нет. Это не аппаратная приёмка времени.
- Найден отдельный дефект послеclose: проверки могли сравнивать стёртые ID, давать
  равенство очищенных snapshots или digest стёртого buffer. Сначала воспроизведён
  падающим synthetic test, затем закрыт guard: file accept/verify/membership/digest/
  summary и snapshot ID/count/membership/retention (обе стороны) послеclose бросают
  IllegalStateException, не выдают false/0/true как проверенные данные. Закрытие
  идемпотентно, wipe сохранён; закрытыйfile не сравнивается даже с пустым snapshot.
- **139 Java +38 Node tests PASS**, assembleDebug/lintDebug PASS. Literal protobuf
  с двумя synthetic7byte IDs/верхними битами timestamp/timezone, request exact bytes,
  mutation любого из7bytes, owned snapshots после Proto.close и closed-object guards.
  Ни исходники upstream, ни реальные ключи/ID/история в fixtures не копировались.

**BLOCKED:** участие lifetime-дефекта в аппаратном mismatch не показано; эту правку не
считать его решением. Source/routing, hardware exact-target/retention, time/value/coverage
и полная production поддержка всё ещё требуют подтверждения. ACK8/5 не отправлялся.

### 2026-11-13: варианты mismatch и past-only exact routing (probe0.28–0.29)

Проверены только новые bounded header-only режимы isolated probe; production APK,
настройки/time/live, клинический импорт и сервер не затрагивались. ACK8/5 не отправлялся.

| Сборка / отдельный сеанс | Результат | Граница доказательства |
|---|---|---|
| 0.28/schema16 today-only8/1 | 6912ms/6s окно;7files после response/transportACK,973B bulk/945B file data, все вне today index | Неизвестны source и transport boundary на deadline; без8/2/8/3 |
| 0.28 target scan offset0 | 881ms;10pre-request +6post-request files/1763B file data, все6post-request mismatch | Один fetch, расширение2→6 не дало exact; остановка на диагностическом лимите |
| 0.28 target scan offset1 | 1169ms;10pre-request +6post-request files/1456B file data, все6post-request mismatch | Другой target тоже не получен в этом окне; не доказательство отсутствия файла |
| 0.28 past-only8/2 повтор | 7047ms/6s окно;10files/908B bulk/868B file data, все post-response и вне past index | Nearest ID differences только timestamp bytes (mask2/3); timezone normalization не решает эти различия |
| 0.29/schema17 past-target offset0, без8/1 | 852ms;10mismatch, затем11-й файл exact/CRC/pad;target1394B,2262B всех файлов | Fresh-after-request exact ID входит в past snapshot; parser/retention не запускались |
| 0.29/schema17 past-target offset1, без8/1 | 937ms;10mismatch, затем11-й файл exact/CRC/pad;target98B,966B всех файлов | Выбор offset1 в свежем сеансе; межсеансовые ID не сравнивались; today snapshot отсутствует, membership=null |

Во всех шести сеансах SPPv2/auth/HMAC/exact M2345B1/2.3.98 PASS, все completed files
CRC/padding valid и `parser_status=not_parsed`. В today-only иpast-only окнах повторов
ID не было; межсеансовая идентичность данных не проверялась. Количества/размеры не
доказывают равенство файлов и не определяют источник потока.

**Новый узкий PASS:** порядок past8/2 → один past8/3, без промежуточного today8/1,
со строгим demux дал exact target для двух offsets. Возможность получить нужный файл
подтверждена, но происхождение посторонних файлов, общая маршрутизация/time/value,
retention/coverage и полная поддержка истории остаются **BLOCKED**. Исключение today-запроса
и увеличение post-request lookahead проверены вместе; их вклад по отдельности не установлен.

Лимиты не сняты:16files/512KiB всего сеанса, max32bulk во время protobuf wait,
file-stage30s/session75s, без retry/reconnect и без parser даже при exact. Новая
диагностика сравнивает ID только в private памяти; наружу counts/7bit position masks,
при пустом snapshot null, при неоднозначных ближайших masks mask=null. Не ослабляет exact gate.

**148 Java +42 Node PASS**, assembleDebug/lintDebug/editor PASS. Сначала host validation,
затем install-r только isolated probe. В ходе тестирования исправлено неверное ожидание
числа synthetic pre-exact файлов; аппаратные данные не использовались в fixtures.
После каждого сеанса socket закрыт/temporary key absent/Mi Fitness process running;
финально ключи обоих diagnostic packages иproduction lock отсутствуют. BT reconnect
Mi Fitness и визуальное состояние браслета не подтверждены. Reset/unpair/clear/SET/
historyACK/raw persistence/публикации ключей или истории не было.

### 2026-11-13: exact past HR/step aggregates и retention (probe0.30/schema18)

Реализован явный `history_past_target OFFSET 1`: auth/battery/exact M2345B1/2.3.98/SPPv2,
**past8/2 → один past8/3 → повторный past8/2**. Нет промежуточногоtoday-запроса.
Только fresh-after-request exact ID/CRC/pad допускается к daily aggregate parser;
quarantined/prestarted/retention files не разбираются. Budget16files/512KiB на весьсеанс,
включая chunkheaders иretention; file30s/index6s/session75s. Необоснованного retry/ACK нет.

| Hardware-сеанс | Exact sample | Presence aggregates | Full past multiset retention |
|---|---|---|---|
| Offset0,1403ms | 11-й файл,1394B,daily_details_v4 | 86records;86HR-present/0HR-missing;86step-field records | checked=true,unchanged=true |
| Offset1,1410ms | 11-й файл,98B,daily_details_v4 | 5records;5HR-present/0HR-missing;5step-field records | checked=true,unchanged=true |

В обоих сеансах10mismatch→exact→3header-only retention files,14completefiles всего;
4703B/3407B filedata соответственно, всеCRC/padvalid. Parsed ровно1 файл, остальные
`not_parsed`; exact membershippast=true/today=null. Индекс10detail candidates, выбран1,
пропущено9. Межсеансовое равенство/различие ID не проверялось,offset не durable cursor.

Узкий **PASS** для exact-only aggregate чтения и сравнения двух полных past snapshots.
**Не PASS** для абсолютного времени,timezone,числовых значений,непрерывности/гранулярности,
полноты истории,архивной сохранности илиclinical import. HR presence основан наformat validity
bit, не на независимой сверке измерения; step records — не сумма шагов. Статусoverall
`partial`, code=`verified_target_sample`,time_values_verified=false/full_coverage_verified=false.
Неизвестные layouts не получают придуманные HR/step counts. Watermark не менялся.

**157 Java +44 Node PASS**,assembleDebug/lintDebug/editor PASS. Новыеregressions покрывают
exactafter10,synthetic14file shape,prestarted quarantine,parser unsupported,retention reorder/
loss/duplicate/missing/invalid/partial/CRC/pad/flood,shared byte/file budgets,timeout/cancel,
model/firmware/SPP/range guards,stale report rejection,safe aggregate projection иcleanup.
Fixtures целиком синтетические,безреальной истории. Runtime-код прошёлhostvalidation до
двухаппаратныхсеансов; последобавленияtest-onlyregressions suitesповтореныбезновогоhardware.

Install-r толькоизолированногоprobe,безclear. В обоихсеансахsocketclosed,keyabsent,
MiFitnessprocessrestored; финально ключиобоихdiagnosticpackages иproductionlockотсутствуют.
BluetoothпереподключениеMiFitnessнепроверено. ACK8/5,SET,time,live,reset/unpair,clinical
upload,rawhistorypersistence,публикацийkeys/ID/HRvaluesнебыло. ProductionhistoryостаётсяBLOCKED.

### 2026-11-13: time/value encoding diagnostics (probe0.31/schema19)

Pinned public DailyDetailsParser/ComplexActivityParser/FileId сверены; ссылки и полный
контракт полей — в [probe README](../tools/band9-probe/README.md#диагностика-времени-и-кодирования-значений-v031schema19).
Шаги — validity-gated младшие14бит, HR — validity-gated unsigned byte. Upstream не
подтверждает HR0/255 как missing sentinels. Time candidate: descriptor epoch +60s/record,
без повторного timezone offset; upstream signed int, здесь unsigned candidate с отдельным
signed/unsigned-agreement flag. Это гипотеза отображения времени, не clinical timestamp.

| Hardware | Exact-only aggregates | Clock candidate | Retention |
|---|---|---|---|
| Offset0,1060ms | ordinal11/1394B/86records;HR-valid86,encoded0=78,encoded255=0;steps zero82/nonzero4 | minute-aligned/civil-zone/signed-agree;within_24h;last-not-future | full past multiset unchanged |
| Offset1,1276ms | ordinal11/98B/5records;HR-valid5,encoded0=5,encoded255=0;steps zero5/nonzero0 | Те же checks | full past multiset unchanged |

**Существенное уточнение:** HR-present86/5 из0.30 — counts format validity, не counts
реальных измерений. Encoded0 нельзя публиковать как0bpm; его семантика и значения остальных
байтов ещё требуют независимой сверки. Ни значения, ни даты/ID/clock snapshot не выводились.
Оба сеанса partial/time_values_verified=false/full_coverage_verified=false. По14completefiles
(10quarantine→exact→3retention), parsed толькоordinal11; budgets/send gates прежние.

**164 Java +45 Node PASS**,assemble/lint/editorPASS. Tests: zero/255/absent validity,
14-bit flags/extra alignment, unsigned timestamps/overflow, ±zones/no double offset,
minute/age edges, unavailableclock/emptyrecords→null, unsupportedlayout/no invented counts,
clock read толькоexact, safe projection/stale0.30rejection. Сначала телефон былlocked;
после ручной разблокировки пользователем установленisolated0.31install-r и выполнены2сеанса.
Socketclosed/keyabsent/MiFitnessprocessrestored в обоих; финально обаkeyfiles иproductionlock
absent, Bluetoothreconnect не проверен. ACK8/5/SET/time/live/reset/unpair/clear/import не было.

Узкий PASS: decoding layout/aggregate diagnostics и непротиворечие двум phone-clock
snapshots. **BLOCKED:** независимая абсолютная time/timezone/value сверка, семантикаHR0,
гранулярность/пропуски/полнота/архивныйrepeat, production import. Phone clock verified=false;
возрастwithin_24h не доказывает правильность даты. Productionhistory/today/live/SET выключены.

### 2026-11-13: повтор того же past ID без ACK (probe0.32/schema20)

Добавлен явный `history_past_repeat OFFSET 1`: **past8/2 → 8/3 → 8/3 → past8/2**.
Оба fetch содержат один исходный 7-byte ID; второй допускается только после первого
fresh exact/CRC/pad/supported daily_details_v4. Нет повторного выбора offset, третьего
fetch, ACK8/5, today, SET или импорта. Общие 16 files/512KiB на все стадии; один file
deadline 30s и 4096 messages на оба чтения, index 6s/session 75s. Все ограничения прежние.

| Hardware | Exact чтения | Equality | Full past multiset |
|---|---|---|---|
| Offset0,1118ms | ordinal11 и15, по1394B/86records;2788B target data | private SHA-256 identical;format aggregates consistent | checked=true,unchanged=true |
| Offset1,1459ms | ordinal11 и15, по98B/5records;196B target data | Те же проверки | checked=true,unchanged=true |

Оба результата partial/`verified_target_repeat_sample`, time_values_verified=false и
full_coverage_verified=false. В каждом 15 complete files: 10 quarantine → exact →
3 quarantine → exact. Промежуточные три файла получены в repeat_wait; это не доказывает
их причинную связь со вторым fetch. Уже queued exact-передача, ещё не начатая assembler,
неотличима от новой: проверены две одинаковые передачи одного ID после двух отправок,
**не** causal matching или сохранность архива между сеансами. Межсеансовые ID не сравнивались.
HR0 и time/value semantics всё ещё требуют независимой сверки; counts не измерения.

**173 Java +48 Node PASS**, assemble/lint/editor PASS. Fixtures синтетические: два одинаковых
ID, один deadline, changed bytes при same counts, rechunking, observed 15-file routing,
prestarted quarantine, unsupported first/second, malformed second, shared byte/file bounds,
timeout/cancel/EOF, retention loss/duplicate/partial/flood, guards, safe projection и digest
wipe включая observer failure. Два test-only сценария добавлены после hardware, runtime
не менялся. Digest хранится только в RAM и очищается после сравнения/при ошибке; в report
только equality booleans и completed-read count, без raw IDs/дат/значений/digest.

Только isolated install-r, без clear. В обоих сеансах socket closed, key absent и Mi Fitness
process restored; финально оба keyfiles и production lock отсутствуют. BT reconnect не
проверен. Clinical data, watermark и production gates не менялись. Узкий PASS только для
same-session repeated exact transfer и двух snapshots; полная production-история BLOCKED.

## Production foundation — отдельная приёмка

Новый код не использует probe как production-драйвер. Прежние результаты B9-01…B9-21
не являются доказательством работы приложений. Новый QR v3 и явные протоколы описаны в
[ADR-0014](../android/docs/adr/0014-explicit-band-protocol-readonly-xiaomi.md).

| ID | Проверка | Статус | Граница доказательства |
|---|---|---|---|
| B9-P01 | Legacy Band 6 / новый QR v3 / неизвестный protocol | PARTIAL | Целевые JVM-тесты пациента прошли; аппаратная миграция привязки пациента не запускалась |
| B9-P02 | Один маршрутизируемый сеанс / нет fallback BLE после SPP | PARTIAL | Фейки драйверов: выбор по protocol, закрытие/отмена, поздний callback старого драйвера; не аппаратный reconnect |
| B9-P03 | Production auth / exact firmware / battery / GET settings | PASS | Два независимых изолированных library instrumentation-сеанса нового core прошли: уникальный уже bonded кандидат, permissions, guarded open M2345B1/2.3.98, battery/GET settings, close и удаление ключа. Финальный прогон отдельно подтвердил наличие battery, disabled и interval (только presence booleans, значения не публиковались). Первый сбой `socket_connect` исправлен: false из cancelDiscovery при уже остановленном поиске не считается отказом; активный поиск после отмены по-прежнему отклоняется. Процесс Mi Fitness возвращён, её Bluetooth-переподключение не подтверждено. Не приёмка UI или фона |
| B9-P04 | Запрет history/live/SET / отсутствие ложного прогресса | PARTIAL | Целевые тесты: history отказ до фонового соединения, watermark сохранён; live не стартует/не публикуется; legacy interval не отправляется в Band 9 |
| B9-P05 | Полная история со временем и значениями / today steps | BLOCKED | Прежние неизвестные time/value/summary не подтверждены; production не импортирует sample и не отправляет history ACK 8/5 |
| B9-P06 | Live stop / settings SET с restore | BLOCKED | Фактическая остановка сенсора и SET не подтверждены; production не запускает live и не меняет настройки |
| B9-P07 | Выдача / Keystore / CDM / фон / процесс после смерти | PARTIAL | QR/выдача и readonly background реализованы; 17 isolated storage instrumentation PASS. CDM/Doze/process death/выдача E2E ещё не проверены |
| B9-P08 | Фиктивный пациент / сервер / панель / wear-dependent сценарии | BLOCKED | Нет подтверждённых нормализованных минут Band 9; данные существующего пациента не используются |

Обновление 2026-11-13: общий Android прогон `testDebugUnitTest lintDebug assembleDebug`
прошёл; после исправления бюджета кадров повторно весь protocol suite/lint:
**363 JVM-теста** (пациент 224, персонал 43, protocol 77, architecture 19), оба обычных APK
и все instrumentation APK собраны. 50 Node safety/runner tests PASS. Проверка кода нашла
общий счётчик кадров, из-за которого успешная история могла заблокировать следующий GET;
теперь отдельный лимит 512 обычных кадров при абсолютном 8192, regression PASS.
Это не заменяет аппаратную приёмку и E2E.

На SM-S731B / Android 17 запущены **17/17** synthetic storage instrumentation тестов:
3 пациента (encrypted protocol reopen, legacy Band 6, отсутствующий alias) и 14 персонала
(миграции v1/v2, inventory/check/issuance persistence, AES-GCM/reopen/tamper/concurrency,
missing alias). Только отдельные `ru.kardiomonitor.patient.validation[.test]` и
`ru.kardiomonitor.staff.validation[.test]`, UUID alias/отдельные DB/preferences.
Validation Application — штатный `android.app.Application`, без production composition
root, облака, Worker и Bluetooth. Рабочее приложение пациента не переустанавливалось.
Первый patient запуск был crash из-за SDK runner linkage; androidTest uses-library
исправлен, повтор 3/3 PASS. Это не проверка реальной выдачи, UI, процесса после смерти
или background/CDM/Doze. Сборка стендов: см. [пациент](../android/docs/patient-band9-background.md)
и [персонал](../android/docs/staff-app/testing.md).

Существующие серверные сценарии проверены 41 pytest-тестом на временных SQLite и вымышленных
пациентах: загрузка минут/курсор, часовые пояса/день, live upload/request, admission, планы,
оповещения и тренировочные минуты. Рабочая база не использовалась. Это регрессия сервера,
**не** E2E с реальной историей Band 9.

Текущий общий Node-прогон: 50 тестов helpers, runners и source-safety. В нём проверены блокировка
закрытого телефона/активного probe, передача ключа через stdin без аргументов/вывода,
удаление временного ключа после ошибок/таймаута, восстановление процесса Mi Fitness.
Её Bluetooth-переподключение требует отдельного подтверждения; runner его не объявляет.

## Библиотечная проверка истории, 2026-11-13

Оригинальный Kotlin core `:band-protocol` теперь имеет отдельную opt-in операцию
`verifyPastHistory(offset, repeat)`; это не вызов probe и не clinical ActivitySource.
Один v4 details из past snapshot, необязательное второе чтение тех же 7 байт ID,
затем полный multiset past retention (включая неизвестные ID/дубликаты).
CRC/pad, prestarted/foreign quarantine, общий 30s/512KiB/16 files/4096 bulk messages
и отмена с закрытием сокета; digest/raw ID очищаются, возвращаются только агрегаты
категорий кодировок/booleans. Никаких history ACK 8/5.

**Аппаратно два сеанса новой библиотеки PASS**: `history_repeat 0` и `history_repeat 1`
через `tools/band9-run-production-readonly.mjs`. В каждом auth/HMAC/exact guard,
battery/GET settings, две exact передачи одного ID, byte/aggregate equality и полный
past retention unchanged. Runner публикует только allowlisted booleans, не показания,
даты, ID или digest. Socket закрыт, временный ключ и lock удалены, процесс Mi Fitness
восстановлен; её BT reconnect не подтверждён. Последующая правка счётчика кадров
проверена JVM-регрессией и сборкой, отдельный аппаратный повтор этой правки не выполнялся.

`timeAndValuesVerified=false`, `fullCoverageVerified=false`, `clinicalImportAllowed=false`
в API безусловны. Byte equality не доказывает causal matching ответа, время, смысл HR0,
полноту архива или сутки шагов. Два snapshot не доказывают долговременное хранение;
offset не cursor. Приложения не импортируют эти категории в минуты/сервер и не рисуют
их как пульс/шаги. Background Band 9 пока проверяет только заряд и GET настроек;
старый Band 6 history/background и QR остаются совместимыми.

## Итог первого этапа

Экспериментальный протокол Band 9 работоспособен для подключения, авторизации,
чтения заряда/идентичности и коротких потоков HR. Это ещё **не поддержка Band 9
в приложениях пациента/персонала**. Следующий этап: безопасное чтение настроек пульса,
исследование истории и её подтверждений, проверка суточных шагов и только затем
интеграция с приложением, фоном и тестовым сервером. Непроверенные пункты таблицы
не заменяются результатами host-тестов.
