---
name: new-slice
description: Создать вертикальный слайс бэкенда KardioMonitor (features.<контекст>.<слайс>) — доменные типы, схемы, сценарий, router, фейки, тесты, подключение и контракты import-linter. Использовать, когда задача добавляет новый эндпоинт или сценарий.
argument-hint: <контекст>/<слайс>, например monitoring/daily_summary
---

# Новый слайс: $ARGUMENTS

Правила — в документах, здесь только порядок. Прочитать перед началом:
`docs/architecture.md` (разделы «Анатомия слайса», «Правила зависимостей»), глоссарий
контекста в `docs/domain-model.md`, `docs/api-contract.md`.

## Шаги

1. **Термины.** Каждое новое понятие найти в глоссарии. Нет — добавить строку (термин,
   имя в коде, тип, правила) до кода. Общий с Android термин — сверить имя и правила с
   `../android/docs/domain-model.md`. Имя слайса — `snake_case`, без `utils`/`service`.
2. **Контракт.** Новый эндпоинт описать в `docs/api-contract.md` (путь, поля, коды ошибок)
   до кода. Изменение существующего — skill `/api-change`.
3. **Правила предметной области — в домене, тест первым.** `features/<контекст>/domain/`:
   `@dataclass(frozen=True, slots=True)` с `__post_init__`, ошибка — `DomainRuleViolation`.
   Сначала падающий тест в `tests/features/<контекст>/domain/` (без async и моков).
4. **Порт и реализация** — только если нужны новые данные: `Protocol` в `domain/`,
   реализация в `data/` (SQLAlchemy 2.0, `tables.py`), строка `provide(...)` в
   `provider.py` контекста. Тест репозитория — на SQLite (`tests/database.py`).
5. **Файлы слайса** — начать с минимума (architecture.md, «Начинайте просто»):

   ```
   features/<контекст>/<слайс>/
     __init__.py        # from .router import router — единственный экспорт
     router.py          # APIRouter(prefix="/api/v1", tags=[...], route_class=DishkaRoute)
     schemas.py         # …In / …Out на ApiModel; to_domain() / from_domain()
     <глагол>_<объект>.py  # сценарий — если есть запись в базу или логика без HTTP
   ```

   Сценарий с записью получает `transaction: Transaction` и вызывает
   `await transaction.commit()` до возврата (ADR-0005).
6. **Подключение.** Роутер — в `app/routers.py`. Слайс — **в оба списка** слайсов
   import-linter в `pyproject.toml` («Слайсы не импортируют друг друга» и «Слайсы видят
   порты, а не реализации»).
7. **Фейки** — `tests/features/<контекст>/fakes.py`, рукописные, проверяются mypy по
   `Protocol`. Общие (`FixedClock`, `FakeTransaction`) — `tests/fakes.py`.
8. **Тесты слайса** — `tests/features/<контекст>/<слайс>/`: HTTP-запрос через
   `httpx.AsyncClient` + `ASGITransport`, подмена через `replacing(...)`; проверять статус,
   тело и состояние фейка, `FakeTransaction.commits`. Имена — `test_поведение_по_русски`.
9. **Снимок OpenAPI** — обновить и прочитать дифф:
   ```bash
   uv run python -m kardiomonitor.app.export_openapi > tests/contract/openapi.json
   git diff tests/contract/openapi.json
   ```
10. **Проверка** — показать вывод, не пересказ: `./scripts/check.sh`. Для эндпоинтов
    телефона — ещё `scripts/simulate_phone.py` против запущенного сервера.
11. **Документы.** Глоссарий; дерево пакетов в `docs/architecture.md`; контракт; ADR,
    если принято значимое решение. Клиент на Android — `ClinicJson` и `ClinicJsonTest`.
12. **Ревью** — подагент `architecture-reviewer` по диффу.
