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
| *Реабилитация* (план) | `features.rehab` | Основной | Целевые зоны пульса, тренировки, предупреждения |
| *Обмен с клиникой* (план) | `features.clinic` | Поддерживающий | Передача данных врачу |

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
    db/                       #   KardioDatabase: SQLiteOpenHelper, миграции схемы
    ui/                       #   Тема, карточки (SectionCard, StatTile), графики (без бизнес-логики)
  features/
    band/                     # Контекст «Браслет»
      domain/                 #   Band, BandCredentials, BandAddress, AuthKey, HeartRateMeasurementInterval,
                              #   BatteryLevel, ConnectionStage, ConnectionProblem; порты BandRepository,
                              #   BatteryLog, BandLink (канал связи — реализует ACL)
      api/                    #   Опубликованный API контекста: BandConnection (порт сеанса связи),
                              #   ConnectionState, SessionUnavailableException
      data/                   #   SharedPreferencesBandRepository, SqliteBatteryLog
      pairing/                #   Слайс: привязка браслета (PairingScreen) и смена (ChangeBandButton)
      connection/             #   Слайс: подключение/отключение, статус, заряд (ConnectionSection);
                              #   SingleBandConnection — реализация BandConnection для app
      measurementinterval/    #   Слайс: частота измерения пульса
    monitoring/               # Контекст «Мониторинг»
      domain/                 #   HeartRate, StepCount, Intensity, ActivityMinute, ActivityKind, DailySummary,
                              #   HistorySyncStart, LastSync; порты ActivityHistory, SyncSettings,
                              #   ActivitySource (данные браслета — реализует ACL)
      data/                   #   SqliteActivityHistory, SharedPreferencesSyncSettings
      historysync/            #   Слайс: синхронизация истории (кнопка + HistorySyncWorker)
      liveheartrate/          #   Слайс: пульс сейчас
      stepstoday/             #   Слайс: шаги за сегодня с браслета (в карточке подключения)
      dailysummary/           #   Слайс: итоги дня, графики, навигация по дням
```

Браслет виден контекстам через порты на их языке: `BandLink` (контекст «Браслет») и
`ActivitySource` («Мониторинг»). Оба реализует один объект ACL — `platform.huami.HuamiBand`,
единственный владелец GATT-соединения. Сценарии «Мониторинга» вызывают `ActivitySource`
внутри `BandConnection.withSession { … }`: сеанс гарантирует одно соединение и очередь (ADR-0003).

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
| `platform.*` | Android, библиотеки, доменные типы (только в ACL-адаптере `platform.huami`) | слайсы |
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
  - `CodeRulesTest` — пустые суффиксы имён, файл ≤ 300 строк, без `runCatching`,
    `GlobalScope`, `!!`, `*`-импортов, `Color(0x…)` в контекстах; имена тестов.

  Падение называет правило и строку. **Тест не ослаблять, чтобы он прошёл**: исправить
  код, а если неверно правило — сначала изменить этот документ с согласия человека.
- **Hooks агента** (`.claude/settings.json`): Stop-hook не даёт завершить ход при
  падающих тестах; PreToolUse-hook блокирует очистку и удаление приложения, удаление
  базы на телефоне и вывод ключа. См. [ai-agent-workflow.md](ai-agent-workflow.md).
- **Тесты протокола** с эталонными байтами из Python-реализации (`../huami2021.py`) —
  см. [testing.md](testing.md).
- **Lint** — `./gradlew lintDebug` без новых ошибок.
- Пока не автоматизировано (проверяется ревью): функция ≤ 40 строк, «публична только
  точка входа слайса», бизнес-логика в composable.

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
