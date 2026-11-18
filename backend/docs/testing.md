# Тестирование и проверка

Агент завершает работу, когда «выглядит готово». Тесты превращают «выглядит» в
проверяемый сигнал «прошло / не прошло» — поэтому **у каждой задачи должна быть
команда, которая её проверяет**.

## Команды

Все команды — из `backend/`.

```bash
uv sync                                   # зависимости из uv.lock
./scripts/check.sh                        # полная проверка — перед завершением задачи
uv run pytest tests/features/monitoring/minute_upload   # один слайс
uv run pytest -k "часовом_поясе"          # по части имени теста
uv run ruff format src tests scripts && uv run ruff check --fix src tests scripts
uv run mypy src tests                     # типы (strict)
uv run lint-imports                       # границы слайсов и контекстов
```

`scripts/check.sh` = `ruff format --check` + `ruff check` + `mypy` + `lint-imports` + `pytest`
(~10 с). Ruff ограничен `src tests scripts`: иначе он форматирует примеры кода в `docs/*.md`.

## Что и как тестировать

| Уровень | Что | Как | Где |
|---|---|---|---|
| Домен | Правила: допустимый пульс, пакет минут, итоги дня, день пациента, имя, токен | Чистые pytest-тесты, без моков и без async | `tests/features/<контекст>/domain/` |
| Слайс | Эндпоинт целиком: HTTP-запрос → ответ и состояние фейка | `httpx.AsyncClient` + `ASGITransport`, фейки портов через dishka `override=True` | `tests/features/<контекст>/<слайс>/` |
| Репозиторий | SQL, upsert, порции, часовые пояса | Настоящая SQLite во временном файле (`tests/database.py`) | `tests/features/<контекст>/data/` |
| Сквозной | Телефон регистрируется и шлёт данные → панель их видит | Всё приложение с `lifespan` и настоящей SQLite | `tests/test_end_to_end.py` |
| Контракт | OpenAPI не изменился случайно | Сравнение со снимком `tests/contract/openapi.json` | `tests/contract/` |
| Архитектура | Границы, правила кода | `lint-imports` + `tests/architecture/` | `pyproject.toml`, `tests/architecture/` |

### Правила

1. **Проверяйте результат, а не реализацию.** Тест слайса смотрит на статус и тело ответа и
   на то, что лежит в фейке, а не на то, какие методы вызывались.
2. **Фейки вместо моков.** Рукописный `FakeActivityHistory` со словарём внутри понятнее
   агенту, чем `MagicMock`, и проверяется mypy по `Protocol`. Фейки — в
   `tests/features/<контекст>/fakes.py`; общие (`FixedClock`, `FakeTransaction`) — в `tests/fakes.py`.
3. **Фиксация транзакции проверяется.** Сценарий с записью — `FakeTransaction.commits == 1`
   при успехе и `== 0` при отказе (ADR-0005).
4. **Время — через `Clock`.** Код, которому нужно «сейчас», получает `Clock` из dishka;
   тест подменяет его `FixedClock`. Никаких `sleep` в тестах.
5. **Новое правило предметной области = новый тест** в той же задаче.
6. **Баг = сначала падающий тест**, воспроизводящий его, потом исправление.
7. **Общие с Android правила** (`DailySummary`, `HeartRate`) — на тех же данных, что в
   Android (`DailySummaryTest`): одинаковый вход — одинаковый итог.
8. **Проверка упала — исправляйте код, а не проверку.** Ослабить правило, добавить исключение
   в import-linter или `per-file-ignores` можно только изменив [architecture.md](architecture.md)
   или [coding-rules.md](coding-rules.md) с согласия человека.

### Тест слайса — образец

```python
@pytest.fixture
async def client(history: FakeActivityHistory, transaction: FakeTransaction) -> AsyncIterator[AsyncClient]:
    app = create_app(
        settings_for_tests(),
        replacing(                                   # провайдер dishka с override=True
            (ActivityHistory, history),
            (Transaction, transaction),
            (CurrentPatient, CurrentPatient(PATIENT)),
        ),
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client


async def test_повторная_загрузка_тех_же_минут_не_дублирует_историю(
    client: AsyncClient, history: FakeActivityHistory
) -> None:
    body = {"minutes": [minute_json(10, m) for m in range(30)]}

    await client.post("/api/v1/me/activity-minutes", json=body)
    response = await client.post("/api/v1/me/activity-minutes", json=body)

    assert response.json()["new"] == 0
    assert len(history.minutes_of(PATIENT.id)) == 30
```

`ASGITransport` не запускает `lifespan`: тестам слайсов с фейками база не нужна. Тестам с
настоящей базой — `async with app.router.lifespan_context(app)` (см. `test_end_to_end.py`).
Периодические задачи в тестах выключены (`settings_for_tests`: `periodic_checks=False`):
дневную проверку тест запускает сам — сценарий `check_daily_alerts` с фейками или
`run_daily_check(app.state.dishka_container)` на настоящей базе (`test_alerts_end_to_end.py`).
Тест приёма данных подменяет `MonitoringSubscriber` записывающим фейком (`RecordingSubscriber`).

Превышение тренировочных минут проверяют `tests/features/alerts/domain/test_training_minutes_alert.py`,
`tests/features/alerts/training_minutes/`, SQL-журнал и слайсы плана/ленты, а также
`tests/test_training_minutes_end_to_end.py` и `tests/test_training_minutes_rollback.py`.
Проверки включают 59/60/61 при пороге 60, повторы, исправления, историю версий, полуночь,
немедленную проверку при правке, неизменность просмотра/курсора и откат upload/плана.
`tests/test_schema_upgrade.py` проверяет явное идемпотентное обновление старой схемы:
сохраняются пациент, токен, минуты, версии плана и журнал; старая схема не запускается молча.
Тесты не применяют обновление к рабочему `kardio.db`.

HTTP-заголовки — только ASCII: кириллица в `Authorization` или в cookie в тесте
даёт `UnicodeEncodeError` в httpx, а не проверку сервера.

### Имена тестов

Имя описывает поведение так, чтобы его понял врач или пациент, — фактом, а не пожеланием,
без имени тестируемой функции (по Владимиру Хорикову, skill `test-namer`). Python допускает
кириллицу в идентификаторах — как и в Android-тестах, имена на русском:

```python
# Плохо
def test_heart_rate_validation_raises(): ...
def test_should_return_422_for_invalid_batch(): ...

# Хорошо
def test_пульс_вне_11_249_не_принимается(): ...
def test_минута_без_браслета_не_учитывается_в_покрытии_пульсом(): ...
def test_минута_2359_по_новосибирску_относится_к_своему_дню_а_не_к_следующему_по_utc(): ...
```

**[тест]** `tests/architecture/` проверяет, что имя теста в `tests/features/` содержит кириллицу.

## Проверка вручную

```bash
uv run uvicorn kardiomonitor.app.main:create_app --factory --reload      # /docs — Swagger
uv run python scripts/simulate_phone.py http://127.0.0.1:8000 --live-seconds 120
```

Имитатор делает то же, что телефон: регистрация, история за вчера и сегодня, «пульс сейчас».
Панель — `http://127.0.0.1:8000/` (после `npm run build` во `frontend/`) или
`npm run dev` во `frontend/` (http://localhost:5173).

Сквозная проверка с телефоном — по [../README.md](../README.md): ngrok, регистрация в
приложении, синхронизация, «пульс сейчас». В журнале сервера — `request_id` каждого запроса,
в журнале телефона — тег `KardioServer` (`adb logcat -s KardioServer:*`).

## Ограничения

- Тесты репозиториев — на SQLite, как и демо ([ADR-0007](adr/0007-sqlite-for-demo.md)).
  При переходе на PostgreSQL они переезжают на неё (метка `db`, docker).
- База демо (`kardio.db`) тестами не затрагивается: тесты создают свою во временном каталоге.
