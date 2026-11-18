# Архитектура: Vertical Slice Architecture + DDD

Этот документ — основной источник правил по структуре кода. Код организуется **по
сценариям (вертикальным слайсам)**, а не по техническим слоям; бизнес-понятия
описываются **моделью предметной области (DDD)**. Термины — см. [domain-model.md](domain-model.md).

## Зачем так

Приложение развивается в основном ИИ-агентом. Агенту, как и новому разработчику, нужно:

1. **Быстро найти всё, что относится к задаче.** Сценарий «синхронизировать историю»
   лежит в одной папке, а не размазан по `ui/`, `data/`, `domain/`.
2. **Менять одно, не ломая другое.** Слайсы не зависят друг от друга, поэтому
   правка одного сценария не задевает соседние.
3. **Говорить на языке предметной области.** Имена в коде совпадают с терминами врача
   и пациента: `HeartRate`, `ActivityMinute`, `DailySummary`, а не `Data`, `Item`, `Manager`.

## Основное правило

> **Минимум связей между слайсами, максимум связности внутри слайса.**

Слайс — полный вертикальный срез одного пользовательского сценария: от экрана
(или фоновой задачи) до хранения данных и обмена с браслетом.

## Ограниченные контексты (bounded contexts)

| Контекст | Пакет | Роль | Что внутри |
|---|---|---|---|
| **Мониторинг** | `features.monitoring` | Основной (core) | Минуты активности, пульс, шаги, итоги дня, синхронизация истории, пульс сейчас |
| **Браслет** | `features.band` | Поддерживающий | Учётные данные браслета, сеанс связи, настройки браслета, заряд |
| **Протокол Huami** | `platform.huami` | Общий, за антикоррупционным слоем | Байты, шифрование, UUID, команды Mi Band 6 |
| **Протокол Xiaomi** | `platform.xiaomi`, `:band-protocol` | Отдельный ACL, только проверенные read-only операции | RFCOMM/SPP, auth, заряд, GET settings; exact M2345B1 / 2.3.98 guard |
| **Маршрутизация браслета** | `platform.band` | Общий владелец портов | Явный BandProtocol, один драйвер для BandLink/ActivitySource; без fallback |
| **Реабилитация** | `features.rehab` | Основной | Версии программы и норм, классификация тренировочных минут, локальный прогресс |
| **Оповещения** | `features.alerts` | Основной | Локальный журнал, получение по курсору, системные уведомления и список пациента |
| **Напоминания** | `features.reminders` | Основной | Локальные настройки пациента, правила условий, расписание и системные уведомления |
| **Обмен с клиникой** | `features.clinic` | Поддерживающий | Регистрация телефона на сервере клиники, отправка истории и «пульса сейчас» врачу (демо — через ngrok, сервер — `../backend`) |

Протокол Huami — чужая модель (байты, коды команд, «kind=115»). Он **никогда не
проникает** в контексты `features.*`: адаптер в `platform.huami` переводит байты в
доменные типы (`ActivityMinute`, `HeartRate`, `BatteryLevel`) — это
**антикоррупционный слой (ACL)**.

## Структура пакетов

```
ru.kardiomonitor/
  app/                        # Композиционный корень: KardioApplication, MainActivity (навигация:
                              # привязка / главный экран), AppContainer (ручной DI), DashboardScreen
                              # (раскладка секций слайсов), KardioWorkerFactory, BackgroundSyncSchedule
  platform/                   # Сквозная техника без бизнес-понятий
    ble/                      #   GattClient — очередь GATT-операций на корутинах, withResponseTimeout
    huami/                    #   Протокол Mi Band 6: Auth2021, EcdhB163, Chunked*, MiBand6;
                              #   ACL-адаптер HuamiBand (реализует BandLink и ActivitySource)
    xiaomi/                   #   XiaomiBand: read-only ACL к общему Xiaomi core, без history/live/SET
    band/                     #   BandDriver, ProtocolBandRouter: явный протокол и общий session owner
    db/                       #   KardioDatabase: SQLiteOpenHelper, миграции схемы
    http/                     #   JsonHttpClient — JSON по HTTP без библиотек (ADR-0006), HttpProblemException
    keystore/                 #   AES-256-GCM для секретов пациента, ключ в Android Keystore (ADR-0012)
    ui/                       #   Тема, карточки (SectionCard, StatTile), графики (без бизнес-логики)
  features/
    band/                     # Контекст «Браслет»
      domain/                 #   Band, BandCredentials, BandAddress, AuthKey, HeartRateMeasurementInterval,
                              #   BatteryLevel, ConnectionStage, ConnectionProblem; порты BandRepository,
                              #   BatteryLog, BandLink (канал связи — реализует ACL)
      api/                    #   Опубликованный API контекста: BandConnection (порт сеанса связи),
                              #   ConnectionState, SessionUnavailableException
      data/                   #   SharedPreferencesBandRepository (ключ через Keystore), SqliteBatteryLog
      pairing/                #   Слайс: привязка браслета (PairingScreen) и смена (ChangeBandButton)
      connection/             #   Слайс: подключение/отключение, статус, заряд (ConnectionSection);
                              #   SingleBandConnection — реализация BandConnection для app
      measurementinterval/    #   Слайс: частота измерения пульса
    monitoring/               # Контекст «Мониторинг»
      domain/                 #   HeartRate, StepCount, Intensity, ActivityMinute, ActivityKind, DailySummary,
                              #   HistorySyncStart, LastSync; порты ActivityHistory, SyncSettings,
                              #   ActivitySource (данные браслета — реализует ACL)
                      api/                    #   ActivityHistoryRefresh — короткое фоновое обновление для других контекстов
      data/                   #   SqliteActivityHistory, SharedPreferencesSyncSettings
      historysync/            #   Слайс: синхронизация истории (кнопка + HistorySyncWorker)
      liveheartrate/          #   Слайс: ручной пульс сейчас и ТР-09 — запрос, outbox, foreground Worker
      stepstoday/             #   Слайс: шаги за сегодня с браслета (в карточке подключения)
      dailysummary/           #   Слайс: итоги дня, графики, навигация по дням
    clinic/                   # Контекст «Обмен с клиникой»
      domain/                 #   ServerAddress, PatientName, EnrollmentCode, UploadToken, ServerRegistration,
              #   MinuteReport, LiveHeartRateReport; порты ClinicServer, ClinicEnrollment
      data/                   #   HttpClinicServer + ClinicJson (API v1), регистрация (токен через Keystore)
      enrollment/             #   Слайс: обмен кода QR v2 на регистрацию пациента
      registration/           #   Слайс: регистрация на сервере по адресу и имени (ServerRegistrationSection)
      minuteupload/           #   Слайс: отправка новых минут после каждого пополнения истории (MinuteUploader + секция)
      liveheartrateshare/     #   Слайс: отправка «пульса сейчас» во время измерения (LiveHeartRateSharing)
    rehab/                    # Контекст «Реабилитация»
      domain/                 #   План, версии, этапы, нормы, RehabActivityMinute, дневной и общий прогресс
      data/                   #   Patient-owned JSON-кэш плана в SharedPreferences
      plansync/               #   Слайс: загрузка GET /api/v1/me/rehab-plan и обновление кэша
      progress/               #   Слайс: общий прогресс и норма выбранного дня; ACL из ActivityMinute
    alerts/                   # Контекст «Оповещения»
      domain/                 #   PatientAlert, журнал, уведомитель и статус синхронизации
      api/                    #   Опубликованное управление синхронизацией и тексты пациенту
      data/                   #   Пациентский JSON-журнал и курсор GET /api/v1/me/alerts
      alertsync/              #   Слайс: загрузка страниц и системные уведомления Android
      list/                   #   Слайс: список, ручное обновление и разрешение уведомлений
    reminders/                # Контекст «Напоминания»
      domain/                 #   Настройки, дневные факты и чистые правила трёх видов
      api/                    #   Действия системного уведомления
      data/                   #   Локальный JSON настроек и фактов показов
      remindercheck/          #   Слайс: обновление истории, Worker и системные уведомления
      settings/               #   Слайс: настройки пациента и «не напоминать сегодня»
```

Браслет виден контекстам через порты на их языке: `BandLink` (контекст «Браслет») и
`ActivitySource` («Мониторинг»). Оба реализует один `platform.band.ProtocolBandRouter`,
который выбирает `HuamiBand` (BLE) или `XiaomiBand` (SPP) по сохранённому protocol.
Один драйвер владеет обоими портами, fallback после ошибки запрещён (ADR-0014).
`ActivitySource` работает только при открытом соединении; непроверенный Band 9 monitoring
отклоняется ещё до фонового подключения и не возвращает sample/нули как полную историю:

- выгрузка истории — внутри сеанса: `withSession` (фон, подключается сам) или `withOpenSession`
  (с экрана, соединение не открывает) — сеанс гарантирует одно соединение и очередь (ADR-0003);
- непрерывные потоки («пульс сейчас», шаги сегодня) — пока `BandConnection.state` подключён,
  **не** внутри `withSession` (иначе они держали бы очередь и блокировали синхронизацию);
  «пульс сейчас» оборачивается в `holdOpen`, чтобы свёрнутое приложение не закрыло соединение.

Обмен с клиникой видит мониторинг через доменные типы и события, на своём языке:

- слайс `minuteupload` подписан на `ActivityHistory.changes` («история пополнилась» — синхронизация
  с экрана и из фона) и переводит `ActivityMinute` в `MinuteReport` — язык API сервера;
- слайс `liveheartrateshare` подписан на `LiveHeartRateFeed` («пульс сейчас измерен»): значения
  публикует слайс `liveheartrate`, второе измерение не запускается;
- ТР-09: `HistorySyncEvents` публикуется после каждой попытки синхронизации, а слайс
  `monitoring.liveheartrate` через `ClinicServer` забирает запрос, устойчиво хранит запрос и
  упорядоченный outbox, затем запускает один `connectedDevice` foreground Worker. Ручной и
  клинический режимы используют один `LiveHeartRateMeasurement` и общий `BandConnection`;
- домен и `data` клиники не импортируют чужой домен (правила ниже), поэтому у клиники свои
  типы того, что уходит на сервер; перевод — в слайсах. Контракт — `backend/docs/api-contract.md`.

Реабилитация получает план через порт `ClinicServer`, но хранит и считает его на своём языке.
Слайс `rehab.progress` переводит `monitoring.domain.ActivityMinute` в минимальный
`RehabActivityMinute`; домены контекстов друг друга не импортируют. История запрашивается по
IANA-часовому поясу плана, поэтому границы суток корректны при DST. `RehabPlanSynchronizer`
подписан на регистрацию и изменения истории; сетевой сбой оставляет последний кэш доступным.

Оповещения получает отдельный контекст `features.alerts`. `PatientAlertSynchronizer` выбирает
журнал владельца телефона при изменении регистрации и читает `GET /api/v1/me/alerts` после
успешной отправки минут. Курсор и идентификаторы уже показанных системных уведомлений сохраняются
вместе с журналом: продлённый сервером эпизод обновляет строку, но не уведомляет второй раз.
Контекст клиники публикует только `ClinicSyncEvents`, поэтому слайсы друг друга не вызывают.
HTTP-адаптер на каждой странице передаёт повторяемый `supported_kinds` со всеми пятью известными
видами: `high_heart_rate`, `daily_target_missed`, `daily_target_met`, `low_activity`,
`training_minutes_exceeded`. Без параметра сервер оставляет прежние четыре вида старым клиентам.
Разбор пропускает неизвестные будущие виды до чтения их полей, но сохраняет курсор и `has_more`
всей страницы: даже пустой после фильтрации список не завершает пагинацию раньше времени.
`training_minutes_exceeded` переводится из `PhoneAlertReport` в дневной снимок `PatientAlert`:
минуты и порог первого превышения хранит сервер, телефон не рассчитывает превышение и не меняет
снимок при изменении локальной истории или плана. Список и системный канал используют общие
`PatientAlertText`: день пациента, фактические минуты и сохранённый порог.

Напоминания полностью локальны и не зависят от сервера. `ReminderWorker` запускается WorkManager
раз в 15 минут без разрешения точных будильников и перед условием вызывает опубликованный порт
`ActivityHistoryRefresh`: если браслет недоступен, правила используют уже сохранённую историю.
Слайс переводит минуты мониторинга и результат нормы реабилитации в минимальные ACL-типы своего
домена. WorkManager восстанавливает периодическую работу после перезагрузки и смены часового пояса.

Тесты лежат в `src/test/java/ru/kardiomonitor/<тот же путь>` — рядом с кодом слайса
по пакету.

## Анатомия слайса

```
features/monitoring/historysync/
  HistorySyncSection.kt        # ТОЧКА ВХОДА: @Composable-секция для экрана
  HistorySyncWorker.kt         # ТОЧКА ВХОДА для фона (+ schedule()/cancel())
  SyncHistory.kt               # Сценарий (application service): оркестрация, без UI
  HistorySyncViewModel.kt      # Состояние экрана слайса (internal)
```

Правила слайса:

1. **Одна точка входа на сценарий.** Для экранных слайсов — одна публичная
   `@Composable fun <Slice>Section(...)` или `<Slice>Screen(...)`. Для фоновых — Worker
   и функция планирования. Всё остальное — `internal` или `private`.
2. **Слайс владеет своими типами.** Своё `UiState`, свои события, свой ViewModel.
   Не переиспользуйте `UiState` другого слайса.
3. **Сценарий — тонкий.** `SyncHistory` координирует шаги (взять сеанс → выгрузить →
   сохранить), а правила (что считать валидным пульсом, как считать итоги дня)
   живут в доменных типах. Если в сценарии появляется `if` про предметную область —
   переносите его в домен.
4. **Начинайте просто.** Новый слайс может быть одним файлом (секция + ViewModel).
   Отдельный класс сценария появляется, когда логику нужно тестировать без UI
   или вызывать из двух мест (экран и Worker).

## Правила зависимостей

```
app ──► features.*.<slice> ──► features.*.domain ◄── features.*.data
                │                     ▲
                └──► features.X.api ──┘          platform.* ◄── features.*.data, app
```

| Кто | Может импортировать | Не может |
|---|---|---|
| `features.*.domain` | только Kotlin stdlib, `kotlinx.coroutines.flow`, `java.time` | `android.*`, `platform.*`, `data`, слайсы, Compose |
| Слайс `features.X.<slice>` | домен своего контекста, `features.Y.api`, `features.Y.domain` (только типы), `platform.ui` | **другие слайсы**, любые `features.*.data` (реализации создаёт `app`), `platform.*` кроме `ui` |
| `features.X.data` | домен своего контекста, `platform.db`, Android | слайсы, другие контексты, UI |
| `features.X.api` | домен своего контекста | слайсы, `data`, `platform`, Android |
| `platform.*` | Android, библиотеки, доменные типы band/monitoring (только в ACL `platform.huami`, `platform.xiaomi`, `platform.band`); `platform.http` — без доменных типов | слайсы |
| `app` | всё — это композиционный корень | — |

Ключевые следствия:

- **Слайсы не вызывают друг друга.** Если `dailysummary` должен обновиться после
  синхронизации — он подписывается на `ActivityHistory.changes` (Flow репозитория),
  а не вызывает `historysync`. Синхронизация после подключения кнопкой — подписка
  `historysync` на `BandConnection.state`. Показатель одного слайса внутри секции другого
  (шаги сегодня в карточке подключения) вставляет `app` через composable-слот.
- **Слайс может иметь несколько точек входа для разных мест** — экранную секцию и Worker
  (`historysync`), экран и кнопку (`pairing`), секцию и реализацию порта для `app`
  (`connection` — `SingleBandConnection`). Остальное в слайсе — `internal`/`private`.
- **Связь между контекстами — только через `api` и доменные типы.** Мониторинг
  получает сеанс связи через `features.band.api.BandConnection`, не зная о GATT.
- **Зависимости передаются сверху вниз.** `AppContainer` создаёт инфраструктуру и
  передаёт её в ViewModel-фабрики и Worker. Никаких `getInstance()`, сервис-локаторов
  и глобальных синглтонов, кроме самого `AppContainer` в `Application`.

## Доменная модель: тактические паттерны

Подробно — в [domain-model.md](domain-model.md). Кратко:

- **Value Object** — `@JvmInline value class` для одиночных значений (`HeartRate`,
  `BandAddress`, `AuthKey`), `data class` для составных. Валидация в `init`/фабрике:
  невалидный объект создать нельзя.
- **Entity / Aggregate** — объект с идентичностью и инвариантами (`Band`: учётные
  данные + настройки). Изменения — только через методы корня агрегата.
- **Repository** — интерфейс в `domain/`, реализация в `data/`. Только для корней
  агрегатов. Интерфейс в терминах предметной области: `ActivityHistory.minutesOf(day)`,
  а не `query(from, to)`.
- **Domain Service** — операция, которая не принадлежит одному объекту
  (`DailySummary.of(minutes)` — фабрика, а не сервис; сервис нужен редко).
- **Без анемичной модели.** Если у доменного класса только поля, а логика в ViewModel —
  это ошибка проектирования.

## Прагматизм

- **Не строим абстракции заранее.** Общий код выносится, когда реальное дублирование
  появилось в нескольких слайсах (ориентир — 3, но решает здравый смысл). Нет
  `BaseViewModel`, `Repository<T>`, `UseCase<In, Out>` «на будущее».
- **DDD — там, где есть правила.** Чисто технические вещи (тема, графики, GATT-очередь)
  не нужно моделировать агрегатами.
- **Один Gradle-модуль, пока слайсов мало.** Границы держатся соглашениями и
  архитектурными тестами (см. «Защитные механизмы»). Разделение на модули
  `:feature:*` — когда слайсов станет больше ~10 или сборка замедлится (ADR).

## Защитные механизмы

Правила, которые можно проверить автоматически, должны проверяться автоматически —
агент надёжнее следует тестам, чем тексту.

- **Архитектурные тесты** — `src/test/java/ru/kardiomonitor/architecture/`
  ([ADR-0005](adr/0005-architecture-tests-without-library.md)), входят в `testDebugUnitTest`:
  - `DependencyRulesTest` — таблица выше: домен без Android и платформы, слайсы не
    импортируют слайсы и `data`, `api` — только свой домен, платформа не знает о слайсах;
    публичное в слайсе — только точка входа (объявление верхнего уровня, которое использует
    код вне слайса; остальное — `internal`/`private`);
  - `CodeRulesTest` — пустые суффиксы имён, файл ≤ 300 строк, функция ≤ 40 строк
    (`@Composable` ≤ 80), без `runCatching`, `GlobalScope`, `!!`, `*`-импортов,
    `Color(0x…)` в контекстах; имена тестов.

  Падение называет правило и строку. **Тест не ослаблять, чтобы он прошёл**: исправить
  код, а если неверно правило — сначала изменить этот документ с согласия человека.
- **Hooks агента** (`.claude/settings.json`): Stop-hook не даёт завершить ход при
  падающих тестах; PreToolUse-hook блокирует очистку и удаление приложения, удаление
  базы на телефоне и вывод ключа. См. [ai-agent-workflow.md](ai-agent-workflow.md).
- **Тесты протокола** с эталонными байтами из Python-реализации (`../huami2021.py`) —
  см. [testing.md](testing.md).
- **Lint** — `./gradlew lintDebug` без новых ошибок.
- Не автоматизировано (проверяется ревью, подагент `architecture-reviewer`): бизнес-логика
  в composable, правила в домене, а не в сценарии, параметров ≤ 6, вложенность ≤ 3.

## Миграция прототипа (завершена)

Прототип был написан до этих правил и разложен по слоям (`ble/`, `data/`, `ui/`,
`BandManager`, `MainViewModel`). Он перенесён в эту структуру за четыре шага, по коммиту на
шаг; после каждого — `testDebugUnitTest lintDebug assembleDebug`.

| Было | Стало |
|---|---|
| `ble/Huami2021.kt`, `ble/MiBand6.kt` | `platform/huami/` (по типу на файл) + ACL `HuamiBand`; `ActivitySample` с `kind: Int` удалён |
| `ble/GattClient.kt` | `platform/ble/GattClient.kt` |
| `BandManager.kt` (синглтон `get()`) | `features/band/api/BandConnection` + `features/band/connection/SingleBandConnection`, создаётся в `AppContainer` |
| `MainViewModel.kt`, `UiState` | ViewModel и `UiState` в каждом слайсе |
| `ui/DashboardScreen.kt` | `app/DashboardScreen` раскладывает секции; итоги дня — `DailySummary` |
| `data/BandConfig.kt`, `data/BandSettings.kt` | `features/band/domain/` (`BandCredentials`, `Band`) + `features/band/data/`; настройки синхронизации — `features/monitoring/data/` |
| `data/SampleStore.kt` | `platform/db/KardioDatabase` + `features/monitoring/data/SqliteActivityHistory`, `features/band/data/SqliteBatteryLog` |
| `SyncWorker.kt` | `features/monitoring/historysync/HistorySyncWorker.kt` (работа `history-sync`; старая `band-sync` снимается) |
| `dataVersion` в `BandState` | `ActivityHistory.changes: Flow` |

Совместимость с данными на телефоне пациента сохранена: база `samples.db` (схема v2),
SharedPreferences `band` и `band_settings` с прежними ключами, компонент `.MainActivity`
(`activity-alias`). `LEGACY_NAMES` в `CodeRulesTest` пуст — исключений из правил нет.
