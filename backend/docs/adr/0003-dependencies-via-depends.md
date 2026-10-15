# ADR-0003: Внедрение зависимостей через FastAPI Depends, без DI-фреймворка

- Статус: предложено
- Дата: 2026-10-16

## Контекст

Слайсам нужны реализации портов (`ActivityHistory`, `PatientRegistry`) и сессия базы. Слайс
не должен знать о SQLAlchemy и о реализациях из `data/` (architecture.md). Тестам нужно
подменять реализации фейками. DI-фреймворки (dependency-injector, punq) добавляют
контейнеры, провайдеры и декораторы, которые агенту нужно учитывать при каждой правке.

## Решение

Встроенный механизм FastAPI `Depends` — и есть внедрение зависимостей:

- `platform/db` даёт `DbSession` — сессию на запрос из sessionmaker в `app.state`.
- `features/<контекст>/dependencies.py` — провайдеры портов и `Annotated`-псевдонимы:

```python
def activity_history(session: DbSession) -> ActivityHistory:
    return SqlActivityHistory(session)

ActivityHistoryDep = Annotated[ActivityHistory, Depends(activity_history)]
```

- Слайс объявляет `history: ActivityHistoryDep` и видит только порт.
- Сценарий получает зависимости явными именованными аргументами, без `Depends`.
- Ресурсы на всё время работы (engine, HTTP-клиенты) создаёт `lifespan` в `app/main.py`.
- Тест подменяет провайдер: `app.dependency_overrides[activity_history] = lambda: fake`.

## Последствия

- Граф зависимостей виден в сигнатурах эндпоинтов и в `dependencies.py` контекстов.
- Новой библиотеки нет; это стандартный, документированный способ FastAPI.
- Сценарии — обычные функции, тестируются без FastAPI.
- Слайс импортирует `dependencies.py`, который импортирует `data/` — import-linter
  проверяет запрет прямого импорта `data` с `allow_indirect_imports = true`.

## Отвергнутые варианты

- dependency-injector — второй механизм DI рядом с `Depends`, декораторы `@inject`.
- Глобальные синглтоны модулей (`history = SqlActivityHistory()` при импорте) —
  скрытые зависимости, подключение к базе при импорте, сложно подменять в тестах.
