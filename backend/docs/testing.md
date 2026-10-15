# Тестирование и проверка

Агент завершает работу, когда «выглядит готово». Тесты превращают «выглядит» в
проверяемый сигнал «прошло / не прошло» — поэтому **у каждой задачи должна быть
команда, которая её проверяет**.

## Команды

Все команды — из `backend/`.

```bash
uv sync                                   # установить зависимости из uv.lock
docker compose up -d db                   # PostgreSQL для тестов с меткой db и для разработки
uv run alembic upgrade head               # применить миграции к базе разработки
uv run uvicorn kardiomonitor.app.main:create_app --factory --reload   # сервер, http://127.0.0.1:8000/docs

uv run pytest -m "not db"                 # быстрые тесты без базы, секунды
uv run pytest -m db                       # репозитории и миграции на настоящей PostgreSQL
uv run pytest tests/features/monitoring/minute_upload   # один слайс
uv run ruff format --check && uv run ruff check   # стиль и правила кода
uv run mypy src tests                     # типы (strict)
uv run lint-imports                       # границы слайсов и контекстов
```

Полная проверка перед завершением задачи (`scripts/check.sh` повторяет её):

```bash
uv run ruff format --check && uv run ruff check && uv run mypy src tests \
  && uv run lint-imports && uv run pytest
```

Исправить формат и простые замечания: `uv run ruff format && uv run ruff check --fix`.

## Что и как тестировать

| Уровень | Что | Как | Где |
|---|---|---|---|
| Домен | Правила предметной области: допустимый пульс, пакет минут, итоги дня, часовой пояс дня | Чистые pytest-тесты, без моков и без async | `tests/features/<контекст>/domain/` |
| Слайс | Эндпоинт целиком: HTTP-запрос → ответ и состояние фейка | `httpx.AsyncClient` + `ASGITransport`, фейки портов через `app.dependency_overrides` | `tests/features/<контекст>/<слайс>/` |
| Репозиторий | SQL, upsert, часовые пояса, индексы | Настоящая PostgreSQL, метка `@pytest.mark.db` | `tests/features/<контекст>/data/` |
| Миграции | `upgrade head` с нуля, `downgrade -1`, модели совпадают со схемой | `alembic upgrade/downgrade`, `alembic check`, метка `db` | `tests/migrations/` |
| Контракт | OpenAPI не изменился случайно | Сравнение `app.openapi()` с `tests/contract/openapi.json` | `tests/contract/` |
| Архитектура | Границы, правила кода | `lint-imports` + `tests/architecture/` | `pyproject.toml`, `tests/architecture/` |

### Правила

1. **Проверяйте результат, а не реализацию.** Тест слайса смотрит на статус и тело
   ответа и на то, что лежит в `FakeActivityHistory`, а не на то, какие методы вызывались.
   Порядок предпочтения: выходное значение → состояние → взаимодействие.
2. **Фейки вместо моков.** Рукописный `FakeActivityHistory` со словарём внутри понятнее
   агенту, чем `MagicMock`, проверяется mypy по `Protocol` и не ломается при рефакторинге.
   Фейки — в `tests/features/<контекст>/fakes.py`. `unittest.mock` — только для
   внешнего мира, который нельзя подделать иначе (часы — через параметр, не мок).
3. **Репозитории — только на PostgreSQL**, не на SQLite: другие типы времени, другой
   upsert, другие ограничения. Каждый тест — в транзакции, которая откатывается.
4. **Время — параметром.** Код, которому нужно «сейчас», получает `now: datetime` или
   `Clock`-зависимость; тест передаёт фиксированное время. Никаких `sleep` в тестах.
5. **Новое правило предметной области = новый тест** в той же задаче.
6. **Баг = сначала падающий тест**, воспроизводящий его, потом исправление.
7. **Общие с Android правила** (`DailySummary`, `HeartRate`) — тест на тех же данных, что
   в Android (`DailySummaryTest`): одинаковый вход — одинаковый итог.
8. **Проверка упала — исправляйте код, а не проверку.** Ослабить правило, добавить
   исключение в import-linter или `per-file-ignores` можно только изменив
   [architecture.md](architecture.md) или [coding-rules.md](coding-rules.md) с согласия человека.

### Тест слайса — образец

```python
@pytest.fixture
def history() -> FakeActivityHistory:
    return FakeActivityHistory()


@pytest.fixture
async def client(history: FakeActivityHistory) -> AsyncIterator[AsyncClient]:
    app = create_app(test_settings())
    app.dependency_overrides[activity_history] = lambda: history
    app.dependency_overrides[current_patient] = lambda: PATIENT
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client


async def test_повторная_загрузка_тех_же_минут_не_дублирует_историю(
    client: AsyncClient, history: FakeActivityHistory,
) -> None:
    body = minute_batch_json(minutes=30)

    await client.post("/api/v1/me/activity-minutes", json=body)
    response = await client.post("/api/v1/me/activity-minutes", json=body)

    assert response.status_code == 200
    assert response.json()["new"] == 0
    assert len(history.minutes_of_patient(PATIENT.id)) == 30
```

`ASGITransport` не запускает `lifespan`: тесты слайсов не нуждаются в базе. Тесты, которым
нужен полный запуск, используют `asgi-lifespan` или `TestClient` как контекстный менеджер.

### Имена тестов

Имя описывает поведение так, чтобы его понял врач или пациент, — фактом, а не
пожеланием, без имени тестируемой функции (по Владимиру Хорикову, skill `test-namer`).
Python допускает кириллицу в идентификаторах — как и в Android-тестах, имена на русском:

```python
# Плохо
def test_heart_rate_validation_raises(): ...
def test_should_return_422_for_invalid_batch(): ...

# Хорошо
def test_пульс_выше_249_не_принимается(): ...
def test_минута_без_браслета_не_учитывается_в_покрытии_пульсом(): ...
def test_день_пациента_считается_в_его_часовом_поясе(): ...
```

**[тест]** `tests/architecture/` проверяет, что имя теста в `features/` содержит
кириллицу. Для служебного кода без предметного смысла (`platform/`) имя тоже описывает
результат: `test_токен_сравнивается_за_постоянное_время`.

## Проверка вручную

```bash
uv run uvicorn kardiomonitor.app.main:create_app --factory --reload
# Swagger UI: http://127.0.0.1:8000/docs — схема и пробные запросы
curl -s http://127.0.0.1:8000/health
curl -s -H "Authorization: Bearer $KARDIO_DEV_UPLOAD_TOKEN" \
     http://127.0.0.1:8000/api/v1/me/activity-minutes/last
```

Токен для разработки создаётся в базе разработки сценарием регистрации; в репозиторий и
историю команд он не попадает (переменная окружения из `.env`).

Сквозная проверка с телефоном: Android-приложение в debug-сборке указывает на адрес
сервера в локальной сети; в журнале сервера видно `request_id` и число принятых минут.

## Ограничения

- Тесты с меткой `db` требуют запущенного `docker compose up -d db` (или podman) и
  пропускаются с понятным сообщением, если база недоступна, — но в полной проверке
  перед завершением задачи они **должны** выполняться.
- Тестовая база создаётся и удаляется тестами; базу разработки и тем более сервера
  тесты не трогают (адрес — из `KARDIO_TEST_DATABASE_URL`).
