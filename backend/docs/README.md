# Документация бэкенда

Бэкенд KardioMonitor — сервер на Python и FastAPI. Он принимает данные браслетов с
телефонов пациентов (поминутную историю пульса и шагов, заряд браслета), хранит их и
отдаёт клинике. С браслетом сервер напрямую не общается: браслет → Android-приложение
по BLE → сервер по HTTPS.

> Состояние: **кода ещё нет**, документы описывают целевую структуру и правила.
> Каркас создаётся по ним. Решения со статусом «предложено» в [adr/](adr/) нужно
> подтвердить у человека до того, как на них опирается код.

| Документ | Когда читать |
|---|---|
| [architecture.md](architecture.md) | Перед любой задачей, которая добавляет или переносит код: VSA + DDD, структура пакетов, правила зависимостей, анатомия слайса |
| [domain-model.md](domain-model.md) | Когда нужно назвать класс или понять термин: глоссарий, агрегаты, инварианты, открытые вопросы |
| [coding-rules.md](coding-rules.md) | При написании кода: имена, размеры, типы, ошибки, async, FastAPI, Pydantic, SQLAlchemy, логирование |
| [testing.md](testing.md) | При написании тестов и проверке: команды, уровни тестов, фейки, имена тестов |
| [api-contract.md](api-contract.md) | При изменении эндпоинтов: контракт с Android-приложением, формат ошибок, совместимость |
| [ai-agent-workflow.md](ai-agent-workflow.md) | Как ставить задачи агенту и как агенту их выполнять; skills, hooks |
| [adr/](adr/) | Почему сделано именно так. Новое значимое решение — новый ADR по [шаблону](adr/0000-template.md) |

## Принятые и предложенные решения

- [ADR-0001](adr/0001-vertical-slices-and-ddd.md) — Vertical Slice Architecture + DDD (принято)
- [ADR-0002](adr/0002-tech-stack.md) — стек: FastAPI, Pydantic v2, SQLAlchemy 2 async, PostgreSQL, Alembic, uv (предложено)
- [ADR-0003](adr/0003-dependencies-via-depends.md) — внедрение зависимостей через `Depends`, без DI-фреймворка (предложено)
- [ADR-0004](adr/0004-pure-domain-pydantic-at-boundary.md) — домен на dataclasses, Pydantic только на границе API (предложено)
- [ADR-0005](adr/0005-transaction-per-request.md) — одна транзакция на запрос, фиксация до отправки ответа (предложено)
- [ADR-0006](adr/0006-architecture-tests-import-linter.md) — архитектурные тесты: import-linter + pytest (предложено)

Общие для проекта решения — в [android/docs/adr/](../../android/docs/adr/): термины
предметной области бэкенд берёт из [android/docs/domain-model.md](../../android/docs/domain-model.md).

## Источники правил

- [Claude Code: best practices](https://code.claude.com/docs/en/best-practices), [CLAUDE.md и rules](https://code.claude.com/docs/en/memory) — Anthropic
- [FastAPI: документация](https://fastapi.tiangolo.com/) — разделы Bigger Applications, Dependencies
  ([with yield и `scope`](https://fastapi.tiangolo.com/tutorial/dependencies/dependencies-with-yield/)),
  Lifespan Events, Settings, Testing; [release notes](https://fastapi.tiangolo.com/release-notes/)
- [zhanymkanov/fastapi-best-practices](https://github.com/zhanymkanov/fastapi-best-practices) — практики FastAPI из продакшена
- [Architecture Patterns with Python (Cosmic Python)](https://www.cosmicpython.com/) — DDD, репозиторий, порты и адаптеры на Python
- [Pydantic v2](https://docs.pydantic.dev/latest/), [pydantic-settings](https://docs.pydantic.dev/latest/concepts/pydantic_settings/)
- [SQLAlchemy 2.0: asyncio](https://docs.sqlalchemy.org/en/20/orm/extensions/asyncio.html), [Alembic](https://alembic.sqlalchemy.org/)
- [import-linter: типы контрактов](https://import-linter.readthedocs.io/en/stable/contract_types.html)
- [Ruff: правила](https://docs.astral.sh/ruff/rules/), [mypy strict](https://mypy.readthedocs.io/en/stable/existing_code.html#introduce-stricter-options), [uv](https://docs.astral.sh/uv/)
- [RFC 9457 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [mryll/skills: vertical-slice-architecture, test-namer](https://github.com/mryll/skills),
  [booklib-ai/booklib: domain-driven-design](https://github.com/booklib-ai/booklib)
