# Утилита персонала — тестирование и проверка

Общие правила — [../testing.md](../testing.md): результат, а не реализация; фейки вместо моков;
имена тестов — поведение по-русски в обратных кавычках; баг — сначала падающий тест.

## Команды (из `android`, без установки на личный/рабочий телефон)

```bash
./gradlew :staff:testDebugUnitTest :staff:assembleDebug :staff:assembleDebugAndroidTest :staff:lintDebug
./gradlew :band-protocol:testDebugUnitTest         # протокол браслета (эталонные байты)
./gradlew :architecture:testDebugUnitTest          # архитектурные правила всех модулей
./gradlew testDebugUnitTest lintDebug assembleDebug   # полная проверка всей сборки
# Только отдельный разрешённый тестовый телефон/эмулятор; тесты используют отдельные БД и alias:
./gradlew :staff:connectedDebugAndroidTest         # Keystore, миграции, persistence
./gradlew :staff:installDebug                      # НЕ личный телефон с рабочими данными
adb logcat -v time -s KardioStaff:* KardioBLE:* KardioCloud:*
```

## Что и как тестировать

| Уровень | Что | Как | Где |
|---|---|---|---|
| Домен | Метка, `PairingCode`, `CheckOutcome`, свежесть проверки, журнал выдачи, слияние браслетов из облака | Чистые JVM-тесты | `staff/src/test/.../features/<контекст>/domain/` |
| Слайс | Сценарий через точку входа | Фейки портов: `FakeBandInventory`, `FakeBandKeySource`, `FakeBandInspector`, `FakeIssuanceLog`, `FakeSecretBox`, `FixedClock` | `staff/src/test/.../features/<контекст>/<слайс>/` |
| Крипто облака | RC4 со сбросом 1024, ключ RC4, nonce, подписи, шифрование/расшифровка параметров, `clientSign` | Эталонные векторы из `huami-token` | `staff/src/test/.../platform/xiaomi/` |
| Разбор ответов облака | `get_source_list`, `devices` Zepp, ошибки входа | Сохранённые JSON-ответы с **вымышленными** MAC и ключами | `staff/src/test/resources/xiaomi/` |
| Контракт QR | `PairingCode` → v3 обоих protocols; общий fixture, отсутствие MAC-colon/raw hex32 для старого regex, Unknown rejected; QR → ZXing → та же строка | JVM (zxing-core) | `staff/src/test/.../features/issuance/domain/`, `platform/qr/` |
| Band 9 routing | Только выбранный inspector, без fallback; unknown protocol fail closed; Warning вместо готовности, Smart=0/disabled/null раздельно; protocol выбранной выдачи | JVM, фейки портов | `platform/band/`, `features/bandcheck/check/`, `features/issuance/qrdisplay/` |
| Протокол в БД | Миграции v1/v2 → 3 без изменения sealed key; round-trip/reopen inventory/checks/issuances; unknown stored protocol ≠ Band 6 | Инструментальные synthetic тесты, без браслета | `StaffDatabaseMigrationTest`, `StaffProtocolPersistenceTest` |
| Keystore | AES-GCM round-trip обоих протоколов, новый экземпляр box/repository/DB, разный IV, tamper/invalid envelope/missing alias fail-closed; v2 metadata + исходный ciphertext не меняются при миграции | Инструментальные тесты с отдельными alias и БД | `KeystoreSecretBoxTest`, `KeystoreInventoryCompatibilityTest` |
| Импорт ключа/QR | Один ключ, v1 только Band 6, v3 обоих протоколов; MAC/protocol должны совпасть; неизвестный QR, неоднозначность и поля регистрации отвергаются без fallback | JVM | `BandKeyInputTest` |
| Выдача | Перед подтверждением заново сверяются ключ, протокол, последняя проверка; недоступный Keystore и конфликт открытой выдачи не открывают QR/журнал | JVM | `PrepareIssuanceTest` |
| WebView входа | Разрешённые хосты, перехват `watch.do?code=` | Инструментальный тест | `staff/src/androidTest/` |
| Архитектура | Границы модулей и контекстов, секреты не в `Log.*` | Разбор исходников (ADR-0005) | `architecture/src/test/` |
| Устройство | Проверка браслета, показ QR, сканирование приложением пациента | Вручную по журналу | — |

### Эталонные векторы облака

Как для протокола браслета (`/protocol-change`): векторы — **скриптом**, не вручную, команда
генерации — в KDoc теста. Входы фиксированы (тестовые `ssecurity`, `nonce`, параметры), секретов
аккаунта клиники в тестах нет:

```bash
cd ../huami-token
python -c 'from huami_token.mi_crypto import derive_rc4_key, mi_encrypt_params; \
  print(derive_rc4_key("<тестовый ssecurity base64>", "<тестовый nonce base64>"))'
```

`generate_nonce` случаен — в тесте сверять формат (12 байт, последние 4 — минуты эпохи) с
`FixedClock` и фиксированным генератором случайных байтов.

## Изолированная сборка для synthetic storage instrumentation

`./gradlew -PstaffValidation=true :staff:assembleDebug :staff:assembleDebugAndroidTest`
создаёт отдельные пакеты `ru.kardiomonitor.staff.validation` и
`ru.kardiomonitor.staff.validation.test` в `staff/build-validation/`. У обычной сборки
не меняются applicationId или выходные APK. Validation использует `android.app.Application`,
не создаёт StaffApplication/AppContainer и не запускает cloud/production сценарии.
Она предназначена только для storage instrumentation, не для UI или выдачи.

Для разрешённого устройства устанавливаются только эти два validation APK, затем
запускаются `StaffDatabaseMigrationTest`, `StaffProtocolPersistenceTest`,
`KeystoreSecretBoxTest`, `KeystoreInventoryCompatibilityTest` через AndroidJUnitRunner.
Каждый работает с отдельными synthetic DB/alias, без Bluetooth/бонда/истории/выдачи
реальному пациенту. Не заменять этим CDM/UI/process death/E2E-приёмку.

2026-11-13 на SM-S731B / Android 17 в этих validation-пакетах прошли **14/14**
instrumentation тестов четырёх классов. Production пакет персонала не устанавливался,
пациентский пакет не заменялся. Общий JVM прогон персонала: **43/43**; assemble и lint PASS.
Это проверка synthetic storage, а не реальной выдачи или clinical E2E.

## Границы автоматической проверки хранилища

Инструментальные тесты компилируются без устройства (`assembleDebugAndroidTest`), но JVM
не доказывает работу Android Keystore. Тесты используют только синтетические ключи/метки,
отдельные `staff-*-test.db` / UUID-БД и UUID-alias; production `staff.db` и alias не удаляются.
Пересоздание box/repository/SQLite helper проверяет повторное открытие, но не заменяет
проверку после настоящего завершения процесса/перезагрузки и lock/unlock на выделенном
телефоне. Уход QR в фон должен закрывать экран; после возврата нужен новый показ с проверкой.
Эти аппаратные проверки и визуальная проверка размера шрифта не выполняются сборкой APK.

## Band 9: отдельная приёмка foundation

Не запускалась автоматически и не заменяется JVM-тестами. Только отдельный тестовый
телефон без реальных данных пациента, без install/clear рабочих patient/staff APK.

- Явно выбрать `xiaomi-band9-spp`; показать только bonded Classic/Dual. Несколько устройств
  требуют явного выбора MAC; BLE/имя/RSSI не выбирают Band 9 автоматически.
- Отмена/смена протокола сбрасывает выбор, назад закрывает поиск. Отказ CONNECT, BT off,
  пустой список и неправильная модель/firmware не должны вести к чужому протоколу.
- Только HMAC + M2345B1/2.3.98 guard, battery, GET HR settings; штатное закрытие socket
  при успехе, ошибке и cancellation. Никаких SET 8/11, history ACK 8/5, history/live/today steps.
- Отсутствующий заряд/поля остаются неизвестными; 0 = Smart при отдельном disabled.
  Результат — «Чтение завершено с ограничениями», не «Готов к мониторингу».
- QR v3 обоих протоколов считывает обновлённый пациент; legacy Band 6 QR всё ещё читается
  пациентом. Старое приложение не должно принять v3 как Band 6.
- Проверить маленький экран/крупный шрифт, скролл при клавиатуре, отказ прав, изменение
  protocol во время поиска, список из нескольких bonded устройств, отмену проверки.
- Реальные QR, ключи, raw-пакеты и данные пациента не сохранять в отчётах.

## Проверка Band 6 на устройстве

Нужны: телефон сотрудника (Android 9+), Mi Band 6 с известным ключом, телефон с приложением
пациента. Перед проверкой — закрыть Mi Fitness на обоих телефонах (одно соединение, ADR-0003).

| Шаг | Что искать |
|---|---|
| Проверка браслета | `KardioBLE`: `connection state 2, status 0`, `авторизация успешна`, `write 2a39 1401 -> status 0` |
| Неверный ключ | Итог «ключ не подошёл»; в журнале нет значения ключа |
| Показ QR | `adb exec-out screencap -p > s.png` — чёрный кадр (`FLAG_SECURE`) |
| Сканирование | Приложение пациента: `KardioBLE` — `авторизация успешна` с этим браслетом |
| Облако | `KardioCloud: браслетов в аккаунте: N` (без MAC и ключей) |

Данные утилиты на debug-сборке — убедиться, что ключи зашифрованы:

```bash
adb exec-out run-as ru.kardiomonitor.staff cat databases/staff.db > staff.db
sqlite3 staff.db 'select address, length(key_sealed) from clinic_bands'   # открытого ключа нет
```

Файл `staff.db` после проверки удалить: в нём шифротексты ключей клиники.

## Host-валидация foundation (2026-11-12)

- `:staff:testDebugUnitTest`: **34 JVM-теста PASS**, включая v3 обоих протоколов,
  неизвестный protocol, read-only routing, Smart/disabled/null, журнал и legacy Band 6 flow.
- `:staff:assembleDebug`, `:staff:assembleDebugAndroidTest`, `:staff:lintDebug`: PASS.
- `:architecture:test`: PASS после переноса общей идентичности в `shared.BandProtocol`.
- Новые инструментальные миграции v1/v2 и round-trip persistence **скомпилированы, но не запущены**:
  отдельный тестовый Android runtime не использовался. Staff APK не устанавливался,
  реальные браслеты, приложения и данные пациентов не затрагивались.
- Визуальная и аппаратная приёмка staff UI/Classic chooser/QR scanner остаётся отдельной задачей.

## Ограничения

- Эмулятор не подходит для BLE; облако можно проверить на эмуляторе с Google APIs.
- Тестовый аккаунт Xiaomi с одним браслетом — отдельный от рабочего аккаунта клиники,
  иначе тесты разработчика видят ключи браслетов пациентов.
