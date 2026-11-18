# Утилита персонала — архитектура

Те же принципы, что у приложения пациента: Vertical Slice Architecture + DDD
([../architecture.md](../architecture.md), ADR-0001), ручной DI (ADR-0004), архитектурные тесты
без библиотек (ADR-0005). Здесь — только то, что отличается или добавляется.

## Модули Gradle (ADR-0007)

```
android/
  settings.gradle.kts         include(":band-protocol", ":app", ":staff", ":architecture")
  band-protocol/              # Huami + отдельный Xiaomi read-only client — без доменных типов
    src/main/java/ru/kardiomonitor/platform/ble/      GattClient, withResponseTimeout, Hex
    src/main/java/ru/kardiomonitor/platform/huami/    MiBand6, Auth2021, EcdhB163, Chunked*, HistoryPackets
    src/test/…                                         Huami2021Test и др. — эталонные байты
  app/                        # приложение пациента (ru.kardiomonitor) — ACL HuamiBand здесь
  staff/                      # утилита персонала (ru.kardiomonitor.staff)
  architecture/               # только src/test: правила для всех модулей
```

```
:app ───► :band-protocol ◄─── :staff          :architecture — читает исходники, не зависит
```

- `:staff` **не зависит от `:app`**, `:app` — от `:staff`. Общее — только `:band-protocol`.
  Понятия, которые есть в обоих приложениях (`BandAddress`, `AuthKey`), объявлены в каждом
  своими доменными типами: у приложений разные контексты, общая — только строка QR
  ([qr-contract.md](../qr-contract.md)), её совместимость держат тесты обеих сторон.
- `:band-protocol` отдаёт байты и простые типы Kotlin; исключения связи — `IOException`,
  отказ авторизации — `AuthException`. Перевод в доменные типы — ACL каждого приложения.

## Ограниченные контексты утилиты

| Контекст | Пакет | Роль | Что внутри |
|---|---|---|---|
| **Браслеты клиники** | `features.inventory` | Основной | Браслет клиники, метка, ключ (секрет), источник ключей; список, ручное добавление, метки, получение из облака |
| **Проверка браслета** | `features.bandcheck` | Основной | Явный протокол: Band 6 BLE/Huami + запись интервала; Band 9 bonded RFCOMM/Xiaomi только чтение и строгий model/firmware guard |
| **Выдача** | `features.issuance` | Основной | Код привязки (содержимое QR), показ QR, журнал выдачи и возврата |
| **Аккаунт клиники** | `features.account` | Поддерживающий | Вход в аккаунт Xiaomi, сеанс (в памяти), выход |
| **Облако Xiaomi** | `platform.xiaomi` | Чужая модель, за ACL | Вход `serviceLogin`, RC4/подписи, `get_source_list`; OAuth Zepp Life |
| **Протоколы браслетов** | `platform.band` (ACL) над `:band-protocol` | Чужая модель, за ACL | `ProtocolBandInspector` dispatch без probing/fallback; `MiBandInspector`, `XiaomiBand9Inspector`; BLE discovery отдельно от явного bonded Classic chooser |

## Структура пакетов `:staff`

```
ru.kardiomonitor.staff/
  app/                        # Композиционный корень: StaffApplication, MainActivity (Navigation
                              # Compose), AppContainer (ручной DI), StaffNavHost
  platform/
    xiaomi/                   #   ACL облака: XiaomiAccountClient (вход; сеанс — только здесь, в памяти),
                              #   MiCloudCrypto (RC4, подписи, nonce), HealthSourceApi (get_source_list), ZeppLifeClient (запасной);
                              #   XiaomiBandKeySource — реализует inventory.domain.BandKeySource,
                              #   XiaomiAccountGateway — реализует account.domain.AccountGateway
    band/                     #   ProtocolBandInspector → MiBandInspector / XiaomiBand9Inspector
                              #   Band 9: XiaomiReadOnlyClient, HMAC + M2345B1/2.3.98 guard, только GET
                              #   ClassicBandDiscovery — уже bonded Classic/Dual, явный выбор адреса
    keystore/                 #   SecretBox: AES-256-GCM ключом Android Keystore (ADR-0010)
    db/                       #   StaffDatabase: SQLiteOpenHelper, миграции схемы
    qr/                       #   QrImage: строка → ImageBitmap через ZXing core (ADR-0009)
    http/                     #   HTTP без библиотек — по образцу JsonHttpClient (ADR-0006), cookies
    ui/                       #   Тема, карточки — без бизнес-логики
  features/
    account/
      domain/                 #   ClinicAccount, SignInState, SignInResult, порт AccountGateway
      api/                    #   Опубликованный API: состояние входа для других контекстов
      signin/                 #   Слайс: вход паролем / WebView, выход
    inventory/
      domain/                 #   ClinicBand, BandLabel, BandAddress, AuthKey, BandModel, Firmware;
                              #   порты BandInventory (репозиторий), BandKeySource (облако)
      data/                   #   SqliteBandInventory (ключ — через SecretBox)
      bandlist/               #   Слайс: список браслетов, поиск по метке
      manualentry/            #   Слайс: добавление вручную (разбор вставленного текста)
      labeling/               #   Слайс: метка браслета
      cloudsync/              #   Слайс: получить браслеты и ключи из облака
    bandcheck/
      domain/                 #   BandCheck, CheckOutcome, BatteryLevel, TestedFirmware; порт BandInspector,
                              #   репозиторий BandCheckLog
      data/                   #   SqliteBandCheckLog
      check/                  #   Слайс: проверка браслета
    issuance/
      domain/                 #   PairingCode, Issuance, IssuanceLog (репозиторий)
      data/                   #   SqliteIssuanceLog
      qrdisplay/              #   Слайс: показ QR (FLAG_SECURE, яркость, таймер)
      issuancelog/            #   Слайс: журнал выдачи и возврат
```

Связи между контекстами — по правилам приложения пациента: через доменные типы и
`features.<контекст>.api`. Например, `issuance` получает ключ браслета через
`inventory.api.IssuableBands` (опубликованный порт: «браслет, готовый к выдаче, и его код
привязки»), а не читает `inventory.data`; «последняя проверка» — через `bandcheck.api.LastCheck`.

## Persistence и совместимость

`shared.BandProtocol` — общий примитив идентичности протокола без зависимостей между
inventory/bandcheck/issuance domains; неизвестное значение закрывает операции.
`StaffDatabase` schema 3 сохраняет `protocol` в `clinic_bands`, `band_checks`, `issuances`,
nullable `hr_interval_minutes` / `hr_disabled` в проверках. Миграции v1/v2 сохраняют ключи
без расшифровки/перешифрования и помечают прежние строки `mi-band6-ble`; неизвестные новые
значения декодируются как `Unknown`, не Band 6. Проверка для QR должна совпадать по protocol
и быть строго после обновления ключа. QR v3 не читается старым MAC+hex32 parser;
совместимость legacy Band 6 чтения обеспечивает пациент, staff пишет только v3.

Ни один staff-путь Band 9 не обращается к history/live/SET; его успешная проверка всегда
Warning с ограничениями. `0 = Smart` сохраняется отдельно от disabled; unknown — null.

## Правила зависимостей

Таблица из [../architecture.md](../architecture.md#правила-зависимостей) действует с заменой
корня `ru.kardiomonitor` → `ru.kardiomonitor.staff`. Дополнительно:

| Кто | Может | Не может |
|---|---|---|
| `platform.xiaomi` | Android, `platform.http`, доменные типы `inventory`/`account` (только в адаптерах `Xiaomi…Source/Gateway`) | слайсы, `:band-protocol` |
| `platform.band` | `:band-protocol`, доменные типы `bandcheck` | слайсы, облако |
| `platform.keystore`, `platform.qr`, `platform.db` | Android, библиотеки | доменные типы, слайсы |
| любой код `:staff` | `:band-protocol` — **только** из `platform.band` | — |

## Сквозные решения

- **Секреты** — [security.md](security.md): ключи браслетов — только через `SecretBox`;
  сеанс облака — только в памяти; `FLAG_SECURE` на экранах с ключом и QR.
- **Сеть** — HTTPS, cleartext запрещён `networkSecurityConfig`; таймауты; ошибки облака —
  `IOException` в ACL → `sealed`-результат в домене.
- **BLE** — одно соединение (ADR-0003): проверка браслета — короткий сеанс «подключиться →
  проверить → отключиться», без фоновой работы. Отправку `03` (удаление истории) утилита не
  делает (ADR-0002). Разрешения — `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`.
- **Журналы** — теги `KardioStaff` (утилита), `KardioBLE` (протокол из `:band-protocol`),
  `KardioCloud` (облако). Ключи, пароль, `ssecurity`, `serviceToken`, cookies, содержимое QR —
  никогда.
- **Фоновой работы нет** (WorkManager не нужен).

## Защитные механизмы

- **Архитектурные тесты** (`:architecture`, ADR-0005/0007) для всех модулей: `DependencyRulesTest`,
  `CodeRulesTest` + для утилиты — «`:band-protocol` импортирует только `platform.band`»,
  «`AuthKey`/`PairingCode` не попадают в вызовы `Log.*`».
- **Тесты на эталонах**: протокол браслета — в `:band-protocol`; криптография облака — в
  `platform.xiaomi` (векторы из `huami-token`, см. [testing.md](testing.md)).
- **Контракт QR** — одинаковые строки в тестах `:app` и `:staff`.
- **Lint** — `./gradlew :staff:lintDebug` без новых ошибок.

## Инструменты агента

Общие для сборки `android/`: hooks (`unit-tests-on-stop.sh` гоняет `testDebugUnitTest` всех
модулей), подагент `architecture-reviewer`, skills `/new-slice` и `/protocol-change`. После
шага 0 плана в них нужно учесть модули: `/new-slice staff:<контекст>/<слайс>`, протокол — в
`band-protocol/`. Обновить вместе с шагом 0.
