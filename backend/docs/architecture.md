# Архитектура: Vertical Slice Architecture + DDD

Основной источник правил по структуре кода бэкенда. Код организуется **по сценариям
(вертикальным слайсам)**, а не по техническим слоям (`routers/`, `services/`, `models/`);
бизнес-понятия описываются **моделью предметной области (DDD)**. Термины — в
[domain-model.md](domain-model.md). Решение — [ADR-0001](adr/0001-vertical-slices-and-ddd.md).

## Зачем так

Бэкенд развивается в основном ИИ-агентом. Агенту, как и новому разработчику, нужно:

1. **Быстро найти всё, что относится к задаче.** Сценарий «принять минуты с телефона»
   лежит в одной папке: эндпоинт, схемы запроса и ответа, сценарий. Не нужно собирать его
   из `routers/`, `schemas/`, `services/`, `crud/`.
2. **Менять одно, не ломая другое.** Слайсы не импортируют друг друга, поэтому правка
   одного эндпоинта не задевает соседние.
3. **Говорить на языке предметной области.** Имена совпадают с терминами врача и
   Android-приложения: `ActivityMinute`, `HeartRate`, `DailySummary`, а не `Record`,
   `Item`, `DataService`.

## Основное правило

> **Минимум связей между слайсами, максимум связности внутри слайса.**

Слайс — полный вертикальный срез одного сценария: от HTTP-эндпоинта до хранения данных.
Обычно слайс = один или несколько тесно связанных эндпоинтов одного сценария.

## Ограниченные контексты (bounded contexts)

| Контекст | Пакет | Роль | Что внутри |
|---|---|---|---|
| **Мониторинг** | `features.monitoring` | Основной (core) | Приём минут активности с телефона, хранение истории, итоги дня, просмотр истории клиникой |
| **Пациенты** | `features.patients` | Поддерживающий | Пациент, выданный ему браслет, телефон пациента и его токен загрузки, журнал заряда браслета |
| *Доступ* (план) | `features.access` | Общий (generic) | Сотрудники клиники, вход, роли. До появления — см. открытые вопросы в [domain-model.md](domain-model.md) |
| *Реабилитация* (план) | `features.rehab` | Основной | Целевые зоны пульса, тренировки, предупреждения. Термины согласовать с врачом до кода |

Границы и термины совпадают с Android-приложением ([android/docs/architecture.md](../../android/docs/architecture.md)):
`ActivityMinute` на телефоне и на сервере — одно понятие с одними правилами. Протокол
браслета (байты, коды команд) сервер **не знает**: его переводит в доменные типы ACL на
телефоне. Внешний мир для сервера — это JSON API (см. [api-contract.md](api-contract.md)):
JSON-схемы Pydantic живут в слайсах и переводятся в доменные типы на границе.

## Структура пакетов

```
backend/
  pyproject.toml               # зависимости, настройки ruff, mypy, pytest, import-linter
  uv.lock                      # зафиксированные версии (коммитится)
  alembic.ini
  migrations/                  # Alembic: env.py, versions/ГГГГ-ММ-ДД_<что>.py
  compose.yaml                 # PostgreSQL для разработки и тестов
  src/kardiomonitor/
    app/                       # Композиционный корень
      main.py                  #   create_app(settings) → FastAPI: lifespan, роутеры, обработчики ошибок
      settings.py              #   Settings (pydantic-settings), префикс KARDIO_
      routers.py               #   подключение router каждого слайса
      errors.py                #   исключения → ответ application/problem+json
      db_metadata.py           #   импорт таблиц всех контекстов — для Alembic
    shared_kernel.py           # Общее ядро: DomainRuleViolation. Расширять — только через ADR
    platform/                  # Сквозная техника без бизнес-понятий
      db/                      #   engine, sessionmaker, Base с naming convention, DbSession
      web/                     #   ApiModel (база схем), ProblemDetail, middleware request id
      security/                #   хеширование и сравнение токенов
    features/
      monitoring/              # Контекст «Мониторинг»
        domain/                #   ActivityMinute, HeartRate, StepCount, Intensity, ActivityKind,
                               #   MinuteBatch, DailySummary, UploadReceipt; порт ActivityHistory
        data/                  #   tables.py (SQLAlchemy), SqlActivityHistory
        dependencies.py        #   провайдеры портов для Depends: ActivityHistoryDep
        minute_upload/         #   Слайс: приём пакета минут с телефона
        upload_cursor/         #   Слайс: последняя полученная минута (откуда телефону продолжать)
        daily_summary/         #   Слайс: итоги дня пациента для клиники
        minute_history/        #   Слайс: минуты пациента за день для клиники
      patients/                # Контекст «Пациенты»
        domain/                #   Patient, PatientId, BandAddress, PatientPhone, UploadToken,
                               #   BatteryLevel; порты PatientRegistry, BatteryLog
        data/                  #   tables.py, SqlPatientRegistry, SqlBatteryLog
        dependencies.py        #   PatientRegistryDep, BatteryLogDep
        api.py                 #   Опубликованный API контекста: CurrentPatient (по токену телефона)
        enrollment/            #   Слайс: регистрация пациента, выдача браслета и токена телефона
        battery_report/        #   Слайс: приём заряда браслета с телефона
tests/                         # Зеркалит src/kardiomonitor: tests/features/monitoring/minute_upload/...
  architecture/                #   проверки правил кода (см. «Защитные механизмы»)
```

Тесты лежат в `tests/<тот же путь>`, фейки портов — в `tests/features/<контекст>/fakes.py`.

## Анатомия слайса

```
features/monitoring/minute_upload/
  __init__.py                  # from .router import router — единственный экспорт
  router.py                    # ТОЧКА ВХОДА: APIRouter и эндпоинт(ы) слайса
  schemas.py                   # Pydantic-схемы запроса и ответа — контракт API этого слайса
  upload_minutes.py            # Сценарий (application service): оркестрация, без HTTP и SQL
```

```python
# router.py
router = APIRouter(prefix="/api/v1", tags=["Мониторинг"])

@router.post("/me/activity-minutes", summary="Принять минуты активности с телефона")
async def receive_activity_minutes(
    body: MinuteBatchIn,
    patient: CurrentPatient,
    history: ActivityHistoryDep,
) -> UploadReceiptOut:
    receipt = await upload_minutes(patient.id, body.to_domain(), history=history)
    return UploadReceiptOut.from_domain(receipt)
```

Правила слайса:

1. **Одна точка входа.** Наружу слайс отдаёт только `router` (его подключает
   `app/routers.py`). Остальные модули слайса другие пакеты не импортируют.
2. **Слайс владеет своими схемами.** `MinuteBatchIn`, `UploadReceiptOut` — только этого
   слайса. Не переиспользуйте схему другого слайса, даже если поля совпадают: контракты
   эндпоинтов должны меняться независимо.
3. **Эндпоинт — тонкий.** Он переводит HTTP в доменные типы, вызывает сценарий и
   переводит результат обратно. Никакого SQL, правил предметной области и `if` про
   пульс в `router.py`.
4. **Сценарий — тонкий.** Координирует шаги (проверить пакет → сохранить → вернуть
   квитанцию). Правила (допустимый пульс, итоги дня, одна запись на минуту) живут в
   доменных типах. Если в сценарии появляется `if` про предметную область — переносите
   его в домен.
5. **Начинайте просто.** Новый слайс может быть одним `router.py` со схемами и вызовом
   репозитория. Отдельный модуль сценария появляется, когда логику нужно тестировать
   без HTTP или когда `router.py` перерастает ~150 строк.

## Правила зависимостей

```
app ──► features.*.<slice> ──► features.*.domain ◄── features.*.data ──► platform.db
              │        │              ▲    ▲
              │        └─► features.X.dependencies ─► features.X.data
              └──► features.Y.api ────┘    └── shared_kernel
                   platform.web, platform.security
```

| Кто | Может импортировать | Не может |
|---|---|---|
| `features.*.domain` | стандартную библиотеку, `shared_kernel`, домен другого контекста (только типы, например `PatientId`) | `fastapi`, `starlette`, `pydantic`, `sqlalchemy`, `platform`, `app`, `data`, слайсы |
| Слайс `features.X.<slice>` | домен своего контекста, `features.X.dependencies`, `features.Y.api`, `features.Y.domain` (типы), `platform.web`, `fastapi`, `pydantic` | **другие слайсы**, любые `features.*.data` напрямую, `sqlalchemy`, `app` |
| `features.X.dependencies` | домен и `data` своего контекста, `platform.db` | слайсы, другие контексты |
| `features.X.api` | домен и `dependencies` своего контекста, `platform` | слайсы, другие контексты |
| `features.X.data` | домен своего контекста, `platform.db`, `sqlalchemy` | слайсы, другие контексты, `fastapi`, `pydantic` |
| `platform.*` | библиотеки, `shared_kernel` | `features`, `app` |
| `app` | всё — это композиционный корень | — |

Ключевые следствия:

- **Слайсы не вызывают друг друга.** Если итоги дня должны учесть только что принятые
  минуты, `daily_summary` читает `ActivityHistory`, а не вызывает `minute_upload`. Общее
  правило двух слайсов — в доменном типе.
- **Реализации портов знает только `dependencies.py` контекста** (и `app`). Слайс
  объявляет зависимость от порта: `history: ActivityHistoryDep`, где
  `ActivityHistoryDep = Annotated[ActivityHistory, Depends(activity_history)]`. Тест
  подменяет реализацию через `app.dependency_overrides` ([ADR-0003](adr/0003-dependencies-via-depends.md)).
- **Связь между контекстами — только через `api.py` и доменные типы.** Мониторинг узнаёт
  пациента по токену телефона через `features.patients.api.CurrentPatient`, не зная,
  как хранятся токены.
- **Никакого глобального состояния**, кроме того, что `app` кладёт в `app.state` в
  `lifespan` (engine, sessionmaker, HTTP-клиенты). Модули не создают подключения при
  импорте; `settings` не импортируются глобально — значения приходят через `Depends`.

## Доменная модель: тактические паттерны

Подробно — в [domain-model.md](domain-model.md), правила домена на Python —
[ADR-0004](adr/0004-pure-domain-pydantic-at-boundary.md). Кратко:

- **Value Object** — `@dataclass(frozen=True, slots=True)` с проверкой в `__post_init__`:
  невалидный объект создать нельзя (`HeartRate(bpm=300)` → `DomainRuleViolation`).
  Идентификаторы без правил — `NewType` над `UUID` (`PatientId`).
- **Entity / Aggregate** — объект с идентичностью и инвариантами (`Patient`: браслет,
  телефон). Изменения — только через методы корня агрегата.
- **Repository** — `typing.Protocol` в `domain/`, реализация в `data/`. Только для
  корней агрегатов и коллекций предметной области. Методы на языке предметной области:
  `ActivityHistory.minutes_of(patient_id, day)`, а не `query(filters)`.
- **Сценарий (application service)** — функция `async def upload_minutes(...)` в слайсе.
  Зависимости — явными аргументами, не глобальными объектами.
- **Без анемичной модели.** Если у доменного класса только поля, а логика в эндпоинте
  или сценарии — это ошибка проектирования.

## Хранение данных

- PostgreSQL, SQLAlchemy 2.0 (async, `asyncpg`), миграции Alembic ([ADR-0002](adr/0002-tech-stack.md)).
- Таблицы описываются в `features/<контекст>/data/tables.py`; ORM-объекты не выходят
  из `data/` — репозиторий возвращает доменные типы.
- Одна транзакция на запрос, фиксация **до** отправки ответа ([ADR-0005](adr/0005-transaction-per-request.md)).
- Таблица принадлежит одному контексту. Другой контекст не читает её SQL-запросом, а
  получает данные через `api.py` владельца.

## Прагматизм

- **Не строим абстракции заранее.** Общий код выносится, когда реальное дублирование
  появилось в нескольких слайсах (ориентир — 3). Нет `BaseRepository[T]`,
  `BaseService`, `UseCase[In, Out]`, `GenericCRUDRouter` «на будущее».
- **DDD — там, где есть правила.** Проверка живости (`/health`), настройка логов,
  middleware не моделируются агрегатами.
- **Один пакет и один процесс, пока это работает.** Очередь задач, отдельный воркер,
  кеш, микросервисы — только с ADR, когда появилась измеренная потребность.

## Защитные механизмы

Правила, которые можно проверить автоматически, должны проверяться автоматически —
агент надёжнее следует падающей проверке, чем тексту ([ADR-0006](adr/0006-architecture-tests-import-linter.md)).

- **import-linter** (`uv run lint-imports`) — таблица зависимостей выше в виде контрактов
  в `pyproject.toml`:

```toml
[tool.importlinter]
root_package = "kardiomonitor"
include_external_packages = true

[[tool.importlinter.contracts]]
name = "Слои: app → features → platform → shared_kernel"
type = "layers"
layers = ["kardiomonitor.app", "kardiomonitor.features", "kardiomonitor.platform", "kardiomonitor.shared_kernel"]

[[tool.importlinter.contracts]]
name = "Домен — чистый Python"
type = "forbidden"
source_modules = ["kardiomonitor.features.monitoring.domain", "kardiomonitor.features.patients.domain"]
forbidden_modules = ["fastapi", "starlette", "pydantic", "sqlalchemy", "kardiomonitor.platform",
                     "kardiomonitor.features.monitoring.data", "kardiomonitor.features.patients.data"]

[[tool.importlinter.contracts]]
name = "Слайсы не импортируют друг друга"
type = "independence"
modules = ["kardiomonitor.features.monitoring.minute_upload", "kardiomonitor.features.monitoring.upload_cursor",
           "kardiomonitor.features.monitoring.daily_summary", "kardiomonitor.features.monitoring.minute_history",
           "kardiomonitor.features.patients.enrollment", "kardiomonitor.features.patients.battery_report"]

[[tool.importlinter.contracts]]
name = "Слайсы не трогают data и SQLAlchemy напрямую"
type = "forbidden"
source_modules = ["kardiomonitor.features.monitoring.minute_upload", "..."]   # все слайсы
forbidden_modules = ["sqlalchemy", "kardiomonitor.features.monitoring.data", "kardiomonitor.features.patients.data"]
allow_indirect_imports = true      # через dependencies.py — можно
```

  Новый слайс добавляется в контракты в той же задаче (skill `/new-slice` делает это сам).
- **Правила кода** — `ruff check` (размер и сложность функций, async, даты без часового
  пояса, `print`, `assert`), `mypy --strict`, тесты в `tests/architecture/` (длина файла,
  пустые суффиксы имён, имена тестов). См. [coding-rules.md](coding-rules.md).
- **Снимок OpenAPI** — тест сравнивает `app.openapi()` с зафиксированным
  `tests/contract/openapi.json`: случайное изменение контракта с телефоном роняет тесты
  ([api-contract.md](api-contract.md)).

Падение называет правило и место. **Проверку не ослаблять, чтобы она прошла**: исправить
код, а если неверно правило — сначала изменить этот документ с согласия человека.
