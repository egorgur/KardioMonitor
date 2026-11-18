# Архитектура: Vertical Slice Architecture + DDD

Основной источник правил по структуре кода бэкенда. Код организуется **по сценариям
(вертикальным слайсам)**, а не по техническим слоям (`routers/`, `services/`, `models/`);
бизнес-понятия описываются **моделью предметной области (DDD)**. Термины — в
[domain-model.md](domain-model.md). Решение — [ADR-0001](adr/0001-vertical-slices-and-ddd.md).

## Зачем так

Бэкенд развивается в основном ИИ-агентом. Агенту, как и новому разработчику, нужно:

1. **Быстро найти всё, что относится к задаче.** Сценарий «принять минуты с телефона»
   лежит в одной папке: эндпоинт, схемы запроса и ответа, сценарий.
2. **Менять одно, не ломая другое.** Слайсы не импортируют друг друга (проверяет
   import-linter), поэтому правка одного эндпоинта не задевает соседние.
3. **Говорить на языке предметной области.** Имена совпадают с терминами врача и
   Android-приложения: `ActivityMinute`, `HeartRate`, `DailySummary`, а не `Record`, `DataService`.

## Основное правило

> **Минимум связей между слайсами, максимум связности внутри слайса.**

Слайс — полный вертикальный срез одного сценария: от HTTP-эндпоинта до хранения данных.
Обычно слайс = один или несколько тесно связанных эндпоинтов одного сценария.

## Ограниченные контексты (bounded contexts)

| Контекст | Пакет | Роль | Что внутри |
|---|---|---|---|
| **Мониторинг** | `features.monitoring` | Основной (core) | Минуты активности, «пульс сейчас», итоги дня, день пациента, обзор пациентов для панели |
| **Пациенты** | `features.patients` | Поддерживающий | Пациент и его лечащий врач, регистрация телефона, токен загрузки, «текущий пациент» по токену, «пациенты вошедшего врача» (`AttendedPatients`) |
| **Реабилитация** | `features.rehab` | Основной (core) | План реабилитации с версиями, этапы, нормы дня, параметры наблюдения; тренировочные минуты, выполнение нормы, прогресс (ТР-01…ТР-05) |
| **Оповещения** | `features.alerts` | Поддерживающий | Журнал оповещений, «высокий пульс» при приёме данных, дневная проверка по времени, доставка врачу и телефону (ТР-08) |
| **Доступ** | `features.access` | Общий (generic) | Врачи, вход по паролю (Argon2id), сеансы в cookie, журнал доступа (ТР-11, [ADR-0011](adr/0011-clinician-sign-in-argon2-sessions.md)) |

Решение о новых контекстах и план их реализации по требованиям клиники —
[ADR-0008](adr/0008-rehab-alerts-access-contexts.md) и [requirements-design.md](requirements-design.md).

### Зависимости между контекстами

Контексты — слои: `alerts → rehab → monitoring → patients → access`. Контекст импортирует
домен и `api.py` только нижележащих; нижний о верхних не знает (import-linter, контракт
«Контексты — слои»; контекст входит в контракт, когда появляется его пакет). Нижнему нужна
реакция верхнего (минуты приняты → проверить «высокий пульс») — порт в домене нижнего,
реализация в верхнем, связывает `app` ([ADR-0009](adr/0009-reactions-and-periodic-checks.md)).
Экран из данных нескольких контекстов собирает панель из их ответов.

Границы и термины совпадают с Android-приложением ([android/docs/architecture.md](../../android/docs/architecture.md)):
`ActivityMinute` на телефоне и на сервере — одно понятие с одними правилами. Протокол
браслета сервер **не знает**: его переводит в доменные типы ACL на телефоне. Внешний мир
сервера — JSON API ([api-contract.md](api-contract.md)): схемы Pydantic живут в слайсах и
переводятся в доменные типы на границе.

## Структура пакетов

```
backend/
  pyproject.toml               # зависимости; ruff, mypy, pytest, контракты import-linter
  uv.lock                      # зафиксированные версии (коммитится)
  scripts/check.sh             # полная проверка; simulate_phone.py — имитация телефона
  src/kardiomonitor/
    shared_kernel.py           # Общее ядро: DomainRuleViolation, Clock, Transaction. Расширять — через ADR
    app/                       # Композиционный корень
      main.py                  #   create_app(settings, *overrides): контейнер dishka, lifespan (схема базы,
                               #   дневные оповещения раз в минуту), панель
      provider.py              #   AppProvider: Settings, Clock, SessionCookie; MonitoringReactionsProvider —
                               #   подписчики Мониторинга из верхних контекстов (ADR-0009)
      admin.py                 #   команда администратора: врачи, лечащие врачи пациентов (ADR-0011)
      settings.py              #   Settings (pydantic-settings), префикс KARDIO_
      routers.py               #   подключение router каждого слайса
      errors.py                #   исключения → application/problem+json
      db_metadata.py           #   таблицы всех контекстов — для create_all
      export_openapi.py        #   снимок OpenAPI для tests/contract
    platform/                  # Сквозная техника без бизнес-понятий
      clock.py                 #   SystemClock
      periodic.py              #   run_periodically: задача раз в N секунд в процессе сервера (ADR-0009)
      db/                      #   Base, UtcDateTime; DatabaseProvider: engine, сессия, Transaction
      web/                     #   ApiModel, UtcDatetime, ProblemDetail/ProblemError, request id
    features/
      access/                  # Контекст «Доступ»
        domain/                #   Clinician, Login, Password, SessionToken, ClinicianSession, AccessAction;
                               #   доменная служба Authentication; порты Clinicians, ClinicianSessions,
                               #   PasswordHasher, AccessLog
        data/                  #   tables.py, SqlClinicians, SqlClinicianSessions, SqlAccessLog, Argon2PasswordHasher
        provider.py            #   AccessProvider: порты и CurrentClinician по cookie сеанса
        api.py                 #   Опубликованный API: CurrentClinician, session_scheme, SessionCookie
        sign_in/               #   Слайс: вход, выход, кто вошёл
      patients/                # Контекст «Пациенты»
        domain/                #   Patient, PatientId, PatientName, UploadToken; порт PatientRegistry
        data/                  #   tables.py, SqlPatientRegistry
        provider.py            #   PatientsProvider: PatientRegistry, CurrentPatient (по токену)
        api.py                 #   Опубликованный API: CurrentPatient, bearer_scheme, AttendedPatients
        registration/          #   Слайс: POST /register, GET /me
        admission/             #   Слайс: врач создаёт пациента, выдаёт код привязки
        patient_card/          #   Слайс: карточка пациента лечащему врачу
        enrollment/            #   Слайс: телефон обменивает код из QR v2 на токен
      monitoring/              # Контекст «Мониторинг»
        domain/                #   ActivityMinute, HeartRate, StepCount, Intensity, ActivityKind, MinuteBatch,
                               #   DailySummary, PatientDay, LiveHeartRateReading; порты ActivityHistory,
                               #   LiveHeartRateLog; события MinutesReceived, LiveHeartRateReceived и порт
                               #   MonitoringSubscriber (ADR-0009)
        data/                  #   tables.py, SqlActivityHistory, SqlLiveHeartRateLog
        provider.py            #   MonitoringProvider
        minute_upload/         #   Слайс: приём пакета минут с телефона
        upload_cursor/         #   Слайс: последняя принятая минута (откуда телефону продолжать)
        live_heart_rate/       #   Слайс: «пульс сейчас» — приём с телефона и окно для панели
        patient_day/           #   Слайс: минуты и итоги дня пациента для панели
        patient_overview/      #   Слайс: список пациентов и свежесть их данных для панели
      rehab/                   # Контекст «Реабилитация»
        domain/                #   RehabPlan, ProgramRevision, RehabProgram, RehabStage, DailyTargets,
                               #   TrainingHeartRateZone, MonitoringSettings; правила walking_minute_class,
                               #   DailyTargetProgress, RehabProgress; порты RehabPlans, DayOutcomes (кеш итогов дня)
        data/                  #   tables.py (версии плана, этапы — JSON; итоги дня), SqlRehabPlans, SqlDayOutcomes
        provider.py            #   RehabProvider
        rehab_plan/            #   Слайс: план реабилитации — чтение и правка врачом
        day_progress/          #   Слайс: выполнение нормы дня
        rehab_progress/        #   Слайс: прогресс реабилитации — «день X из Y», дни с выполненной нормой
        progress_overview/     #   Слайс: прогресс всех пациентов врача для главной
        targets_for_phone/     #   Слайс: программа и нормы для телефона
        day_outcome_refresh/   #   Слайс без HTTP: подписчик — пересчёт итогов дней пришедших минут
      alerts/                  # Контекст «Оповещения»
        domain/                #   PatientAlert, AlertKind, HighHeartRateEpisode, DailyCheck; правило
                               #   alert_for_episode; порт AlertJournal
        data/                  #   tables.py, SqlAlertJournal
        provider.py            #   AlertsProvider
        high_heart_rate/       #   Слайс без HTTP: подписчик Мониторинга — «высокий пульс»
        training_minutes/      #   Превышение минут: MinutesReceived и rehab.domain.PlanChanged,
                               #   полный сохранённый день, неизменяемый снимок, общая транзакция
        daily_check/           #   Слайс без HTTP: периодическая задача — дневные оповещения
        clinician_alerts/      #   Слайс: оповещения врачу, отметка «просмотрено»
        unviewed_counts/       #   Слайс: непросмотренные оповещения для списка пациентов
        patient_alerts/        #   Слайс: новые оповещения телефону по курсору
tests/                         # Зеркалит src/kardiomonitor; fakes.py, database.py — общие помощники
  architecture/                #   правила кода, которых нет в ruff и import-linter
  contract/                    #   снимок OpenAPI
```

Панель клиники — отдельное React-приложение в `../frontend`; сервер раздаёт её сборку
(`KARDIO_FRONTEND_DIST`) с того же адреса, что и API.

## Анатомия слайса

```
features/monitoring/minute_upload/
  __init__.py                  # from .router import router — единственный экспорт
  router.py                    # ТОЧКА ВХОДА: APIRouter(route_class=DishkaRoute) и эндпоинт(ы)
  schemas.py                   # Pydantic-схемы запроса и ответа — контракт API этого слайса
  upload_minutes.py            # Сценарий (application service): оркестрация, без HTTP и SQL
```

```python
# router.py
router = APIRouter(prefix="/api/v1", tags=["Телефон"], route_class=DishkaRoute,
                   dependencies=[Depends(bearer_scheme)])

@router.post("/me/activity-minutes", summary="Принять минуты активности с телефона")
async def receive_activity_minutes(
    body: MinuteBatchIn,
    patient: FromDishka[CurrentPatient],
    history: FromDishka[ActivityHistory],
    transaction: FromDishka[Transaction],
) -> UploadReceiptOut:
    receipt = await upload_minutes(patient.id, body.to_domain(), history=history, transaction=transaction)
    return UploadReceiptOut.from_domain(receipt)
```

Правила слайса:

1. **Одна точка входа.** Наружу слайс отдаёт только `router` (его подключает
   `app/routers.py`). Остальные модули слайса другие пакеты не импортируют.
2. **Слайс владеет своими схемами.** Не переиспользуйте схему другого слайса, даже если
   поля совпадают: контракты эндпоинтов меняются независимо.
3. **Эндпоинт — тонкий.** Переводит HTTP в доменные типы, вызывает сценарий, переводит
   результат обратно. Никакого SQL и правил предметной области в `router.py`.
4. **Сценарий — тонкий.** Координирует шаги и фиксирует транзакцию (ADR-0005). Правила
   (допустимый пульс, одна запись на минуту, день пациента) живут в доменных типах.
5. **Начинайте просто.** Слайс может быть одним `router.py` (как `upload_cursor`).
   Отдельный модуль сценария — когда есть запись в базу или логику нужно тестировать без HTTP.
6. **Точка входа не обязательно HTTP.** Подписчик событий Мониторинга (`alerts/high_heart_rate`,
   `rehab/day_outcome_refresh`) или периодическая задача (`alerts/daily_check`) — тоже слайс:
   наружу он отдаёт только класс подписчика или функцию задачи, их подключает `app` (ADR-0009).

## Внедрение зависимостей — dishka

[ADR-0003](adr/0003-dependency-injection-dishka.md). Кратко:

- Порт — `typing.Protocol` в `domain/`; реализация — в `data/`; связь — в `provider.py`
  контекста: `provide(SqlActivityHistory, provides=ActivityHistory)`.
- Времена жизни: `Scope.APP` — engine, sessionmaker, настройки, часы; `Scope.REQUEST` —
  сессия, `Transaction`, репозитории, `CurrentPatient`, `CurrentClinician`, `AttendedPatients`.
- В эндпоинте — `FromDishka[Порт]` (роутер с `route_class=DishkaRoute`). В сценарий
  зависимости передаются явными именованными аргументами.
- FastAPI `Depends` — только для HTTP-деталей: `bearer_scheme` и `session_scheme` (кнопка
  Authorize в `/docs`).
- **Эндпоинт панели получает пациента только через `AttendedPatients.require(patient_id)`**:
  объект существует только при сеансе врача, чужой пациент — `404` (ТР-11). Тест
  `test_clinician_access.py` обходит все маршруты с `{patient_id}` из OpenAPI.
- Тест подменяет реализацию провайдером с `override=True` (`tests/fakes.py: replacing`).

## Правила зависимостей

```
app ──► features.*.<slice> ──► features.*.domain ◄── features.*.data ──► platform.db
 │            │                       ▲    ▲
 │            └──► features.Y.api ────┘    └── shared_kernel
 └──► features.*.provider ──► features.*.data         platform.web ◄── слайсы, api
```

| Кто | Может импортировать | Не может |
|---|---|---|
| `features.*.domain` | стандартную библиотеку, `shared_kernel`, домен другого контекста (только типы, например `PatientId`) | `fastapi`, `starlette`, `pydantic`, `sqlalchemy`, `dishka`, `platform`, `data`, `provider`, слайсы |
| Слайс `features.X.<slice>` | домен своего и другого контекста (порты и типы), `features.Y.api`, `shared_kernel`, `platform.web`, `fastapi`, `pydantic`, `dishka` | **другие слайсы**, любые `data` и `provider`, `sqlalchemy`, `app` |
| `features.X.provider` | домен, `data` и `api` своего контекста, `dishka`, `fastapi.Request` | слайсы, другие контексты |
| `features.X.api` | домен своего контекста, домен и `api` нижележащих контекстов, `platform.web`, `fastapi` | слайсы, `data`, `provider`, верхние контексты |
| `features.X.data` | домен своего контекста, `platform.db`, `sqlalchemy` | слайсы, другие контексты, `fastapi`, `pydantic` |
| `platform.*` | библиотеки, `shared_kernel` | `features`, `app` |
| `app` | всё — это композиционный корень | — |

Ключевые следствия:

- **Слайсы не вызывают друг друга.** Панель видит только что принятые минуты, потому что
  `patient_day` читает `ActivityHistory`, а не потому что `minute_upload` его уведомляет.
- **Реализации портов знают только `provider.py` контекста и `app`.**
- **Связь между контекстами — через `api.py` и доменные типы.** Мониторинг узнаёт
  пациента по токену через `features.patients.api.CurrentPatient`, не зная, как хранятся токены.
  «Пациенты» не зависят от «Мониторинга» и «Реабилитации» (слои контекстов, ADR-0008).
- **Никакого глобального состояния.** Engine живёт в контейнере dishka (`Scope.APP`) и
  закрывается в `lifespan`. Модули не создают подключения при импорте; `Settings`
  приходят через контейнер.

## Доменная модель: тактические паттерны

Подробно — в [domain-model.md](domain-model.md), правила домена на Python —
[ADR-0004](adr/0004-pure-domain-pydantic-at-boundary.md). Кратко:

- **Value Object** — `@dataclass(frozen=True, slots=True)` с проверкой в `__post_init__`:
  невалидный объект создать нельзя (`HeartRate(bpm=300)` → `DomainRuleViolation`).
  Идентификаторы без правил — `NewType` над `UUID` (`PatientId`).
- **Entity / Aggregate** — объект с идентичностью и инвариантами (`Patient`).
  Создание и изменения — через методы корня (`Patient.register`).
- **Repository** — `typing.Protocol` в `domain/`, реализация в `data/`. Методы на языке
  предметной области: `ActivityHistory.minutes_between(patient_id, start, end)`.
- **Сценарий** — функция `async def upload_minutes(...)` в слайсе.
- **Без анемичной модели.** Если у доменного класса только поля, а логика в эндпоинте —
  это ошибка проектирования.

## Хранение данных

- SQLite для демо ([ADR-0007](adr/0007-sqlite-for-demo.md)), SQLAlchemy 2.0 async;
  схема — `create_all` при запуске. PostgreSQL и Alembic — перед реальными пациентами.
- Таблицы — в `features/<контекст>/data/tables.py`; ORM-объекты не выходят из `data/`.
- Одна транзакция на запрос; фиксирует сценарий до ответа ([ADR-0005](adr/0005-transaction-per-request.md)).
- Таблица принадлежит одному контексту. Другой контекст может сослаться на неё внешним
  ключом (`activity_minutes.patient_id → patients.id`), но читает её только через `api.py`.
- Хранится ввод, итоги считаются. Исключение — кеш итогов дня `rehab_day_outcomes` для списка
  пациентов (ТР-06, п. 4): источник истины — минуты, кеш можно удалить в любой момент.
- Периодическая задача получает свою REQUEST-область dishka и свою транзакцию (ADR-0009).

## Прагматизм

- **Не строим абстракции заранее.** Нет `BaseRepository[T]`, `BaseService`, `UseCase[In, Out]`,
  `GenericCRUDRouter`. Общий код — когда дублирование стало реальным (ориентир — 3).
- **DDD — там, где есть правила.** `/health`, middleware, логирование — без агрегатов.
- **Один пакет и один процесс.** Очередь задач, кеш, отдельный воркер — только с ADR. Периодические
  задачи и кеш итогов дня — в процессе сервера и в его базе (ADR-0009).

## Защитные механизмы

Правила, которые можно проверить автоматически, проверяются автоматически
([ADR-0006](adr/0006-architecture-tests-import-linter.md)). Всё входит в `scripts/check.sh`.

- **import-linter** (`uv run lint-imports`) — контракты в `pyproject.toml`: слои
  `app → features → platform → shared_kernel`; домен без фреймворков; независимость слайсов;
  слайсы без `data`, `provider` и `sqlalchemy`; контексты — слои (ADR-0008).
  **Новый слайс добавляется в оба списка слайсов в той же задаче.**
- **ruff** — размер и сложность функций, async, даты без пояса, `print`, `assert`.
  **mypy --strict** — типы. **`tests/architecture/`** — длина модуля и функции, пустые
  суффиксы, имена модулей, русские имена тестов.
- **Hooks агента** (`.claude/settings.json`): Stop-hook не даёт завершить ход при падающей
  полной проверке; PreToolUse-hook блокирует разрушительные команды к базе и вывод секретов.
  См. [ai-agent-workflow.md](ai-agent-workflow.md).
- **Снимок OpenAPI** (`tests/contract/openapi.json`) — случайное изменение контракта с
  телефоном роняет тесты ([api-contract.md](api-contract.md)).

Падение называет правило и место. **Проверку не ослаблять, чтобы она прошла**: исправить
код, а если неверно правило — сначала изменить этот документ с согласия человека.
