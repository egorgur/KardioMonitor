# KardioMonitor — сервер

Демо-сервер наблюдения за пациентами в кардиореабилитации: принимает с телефона пациента
поминутную историю пульса и шагов и «пульс сейчас», отдаёт их панели клиники (`../frontend`).
Python 3.13, FastAPI, Pydantic v2, SQLAlchemy 2 async, SQLite (демо), dishka, uv.

## Команды

```bash
./scripts/check.sh                                    # полная проверка — перед завершением задачи
uv run pytest tests/features/monitoring/minute_upload # один слайс
uv run ruff format src tests scripts && uv run ruff check --fix src tests scripts
uv run uvicorn kardiomonitor.app.main:create_app --factory --reload   # /docs — Swagger
uv run python scripts/simulate_phone.py http://127.0.0.1:8000 --live-seconds 120
```

Требования клиники (нормы, тренировочный пульс, прогресс, оповещения) — `../docs/requirements.md`:
реализовывать только требования со статусом «готово к работе» (таблица «Статусы»). Как они
ложатся на контексты и слайсы, этапы и их статус — [docs/requirements-design.md](docs/requirements-design.md).

Git общий на `KardioMonitor/`, настройки агента — свои в `backend/.claude/`: Claude Code
запускать из `backend/`.

## Архитектура — читать перед изменением структуры

Vertical Slice Architecture + DDD, как в Android: контексты `features.patients`,
`features.monitoring`, `features.rehab`, `features.access`, внутри — слайсы-сценарии. Подробно:
[docs/architecture.md](docs/architecture.md). Контексты — слои
`rehab → monitoring → patients → access`: нижний не импортирует верхний
([ADR-0008](docs/adr/0008-rehab-alerts-access-contexts.md)).

- **IMPORTANT: эндпоинт панели получает пациента только через `AttendedPatients.require`** —
  врач видит только своих пациентов, чужой — `404` (ТР-11, [ADR-0011](docs/adr/0011-clinician-sign-in-argon2-sessions.md)).
- **IMPORTANT: слайсы не импортируют другие слайсы.** Наружу слайс отдаёт только `router`.
  Связь между контекстами — через `features.<контекст>.api` и доменные типы.
- Домен (`features.*.domain`) — чистый Python на `dataclasses`: без `fastapi`, `pydantic`,
  `sqlalchemy`, `dishka` ([ADR-0004](docs/adr/0004-pure-domain-pydantic-at-boundary.md)).
- Зависимости — dishka: порт `Protocol` в `domain/`, реализация в `data/`, связь в
  `provider.py`; в эндпоинте — `FromDishka[Порт]` ([ADR-0003](docs/adr/0003-dependency-injection-dishka.md)).
- **IMPORTANT: сценарий с записью вызывает `await transaction.commit()` до возврата**
  ([ADR-0005](docs/adr/0005-transaction-per-request.md)): dishka закрывает сессию после ответа.
- Границы проверяют import-linter и `tests/architecture/`. Новый слайс — в оба списка
  слайсов в `pyproject.toml` и в `app/routers.py`. Упала проверка — исправлять код.
- Имена — из глоссария [docs/domain-model.md](docs/domain-model.md); общие с Android термины
  совпадают по имени и правилам.

## Контракт API

[docs/api-contract.md](docs/api-contract.md). Старые версии приложения работают с новым
сервером: внутри `/api/v1` — только совместимые изменения. Любая правка эндпоинта — skill
`/api-change`, снимок `tests/contract/openapi.json` обновляется осознанно.

## Правила кода

Полностью: [docs/coding-rules.md](docs/coding-rules.md). Главное:

- Value-типы (`HeartRate`, `PatientId`), `StrEnum` вместо строк-кодов; время — только с поясом.
- Модуль ≤ 300 строк, функция ≤ 40 строк, сложность ≤ 10, параметров ≤ 6, вложенность ≤ 3.
- Нарушение правила предметной области — `DomainRuleViolation` → `422 problem+json` с `code`.
  `ProblemError` — только в `router.py`/`api.py`; `HTTPException` не использовать.
- Комментарии, docstring, `detail` ошибок — на русском, идентификаторы — на английском.
- Новая зависимость — только с ADR ([docs/adr/](docs/adr/)) и согласием человека.

## Тесты

[docs/testing.md](docs/testing.md). Новое правило или баг — сначала тест. Имена — поведение
по-русски: `test_пульс_вне_11_249_не_принимается`. Фейки портов через dishka
(`replacing(...)`), не моки.

## Данные пациентов — IMPORTANT

- Пульс — данные о здоровье. Не логировать токены, `Authorization`, cookie сеанса, пароли, имена
  и тела запросов; в логе пациент — только `PatientId`.
- Не коммитить `.env`, `kardio.db*`, выгрузки данных.
- `kardio.db` демо-сервера с данными телефона не удалять без согласия человека; ngrok
  запускает и публикует человек.
- Открытые вопросы [domain-model.md](docs/domain-model.md) (ключ браслета, срок хранения данных,
  выдачи браслетов) и вопросы `В-NN` с пометкой «блокирует» не решать самостоятельно.
- Аккаунты врачей и лечащих врачей меняет только администратор (`app/admin.py`); пароли — не в
  аргументах команд и не в логах.

## Инструменты агента

- Skills проекта: `/new-slice <контекст>/<слайс>`, `/api-change`, `/db-migration <что>`.
- Подагент `architecture-reviewer` — ревью диффа перед отчётом.
- Stop-hook запускает `scripts/check.sh`, если менялся код; PreToolUse-hook блокирует
  разрушительные команды к базе и вывод секретов.

## Порядок работы

[docs/ai-agent-workflow.md](docs/ai-agent-workflow.md): разобраться → план (если файлов
несколько) → реализация в слайсе → `./scripts/check.sh` → обновить docs (глоссарий,
контракт, ADR) → ревью (`architecture-reviewer`). Завершая задачу, показывать вывод
проверок, а не утверждать успех на словах.
