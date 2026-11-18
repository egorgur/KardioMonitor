# ADR-0003: Внедрение зависимостей — dishka

- Статус: принято (решение человека, 2026-10-16; заменяет вариант «только FastAPI Depends»)
- Дата: 2026-10-16

## Контекст

Слайсам нужны реализации портов (`ActivityHistory`, `PatientRegistry`), сессия базы, часы.
Слайс не должен знать о SQLAlchemy и о реализациях из `data/` (architecture.md). Тестам
нужно подменять реализации фейками. Ресурсам нужны разные времена жизни: engine — на всё
приложение, сессия — на запрос.

## Решение

[dishka](https://dishka.readthedocs.io/) с интеграцией FastAPI:

- Каждый контекст объявляет `provider.py` — `Provider` dishka, который связывает порты с
  реализациями из `data/`. Только он (и `app`) знает о `data/`:

```python
class MonitoringProvider(Provider):
    scope = Scope.REQUEST
    activity_history = provide(SqlActivityHistory, provides=ActivityHistory)
```

- `platform/db/provider.py` — engine (`Scope.APP`), сессия и `Transaction` (`Scope.REQUEST`);
  `app/provider.py` — настройки, `Clock`, доступ к панели.
- `app/main.py` собирает контейнер `make_async_container(...)` и вызывает `setup_dishka`.
- Роутер слайса — `APIRouter(route_class=DishkaRoute)`, параметры — `FromDishka[Порт]`:

```python
async def receive_activity_minutes(
    body: MinuteBatchIn,
    patient: FromDishka[CurrentPatient],
    history: FromDishka[ActivityHistory],
    transaction: FromDishka[Transaction],
) -> UploadReceiptOut: ...
```

- Сценарий — обычная функция с зависимостями-аргументами, без dishka и FastAPI.
- FastAPI `Depends` остаётся для HTTP-деталей: схемы безопасности для OpenAPI (`HTTPBearer`
  телефона, cookie сеанса врача — ADR-0011).
- Тест подменяет реализации провайдером с `override=True`
  (`create_app(settings, replacing((ActivityHistory, fake)))`, см. testing.md).

## Последствия

- Слайс импортирует только порт из домена — import-linter запрещает слайсам `data`,
  `provider` и `sqlalchemy` без исключений для косвенных импортов.
- Граф зависимостей виден в провайдерах контекстов; времена жизни заданы явно (`Scope`).
- REQUEST-scope dishka закрывается **после отправки ответа** (middleware вокруг всего
  запроса) — фиксировать транзакцию в финализаторе нельзя, см. ADR-0005.
- Новая зависимость (dishka) — агенту нужно знать её API: `Provider`, `provide`, `Scope`,
  `FromDishka`, `DishkaRoute`, `override=True`.

## Отвергнутые варианты

- Только FastAPI `Depends` с провайдерами в `dependencies.py` — слайс импортирует модуль,
  который импортирует `data/`; нет явных времён жизни; подмена — через `dependency_overrides`
  по функции-провайдеру.
- dependency-injector — тяжелее, декларативные контейнеры и `@inject` с `Provide[...]`.
- Глобальные синглтоны модулей — скрытые зависимости, подключение к базе при импорте.
