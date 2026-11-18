# Документация бэкенда

Бэкенд KardioMonitor — сервер на Python и FastAPI. Он принимает данные браслетов с
телефонов пациентов (поминутную историю пульса и шагов, «пульс сейчас»), хранит их и
отдаёт панели клиники. С браслетом сервер напрямую не общается: браслет → Android-приложение
по BLE → сервер по HTTPS (для демо — через ngrok).

> Состояние: **демо-прототип** — регистрация пациента по имени, приём истории и «пульса
> сейчас», панель клиники (`../frontend`) со входом врача, программа реабилитации и нормы. База — SQLite ([ADR-0007](adr/0007-sqlite-for-demo.md)).
> Как провести демонстрацию — [../README.md](../README.md).

| Документ | Когда читать |
|---|---|
| [architecture.md](architecture.md) | Перед любой задачей, которая добавляет или переносит код: VSA + DDD, структура пакетов, dishka, правила зависимостей, анатомия слайса |
| [domain-model.md](domain-model.md) | Когда нужно назвать класс или понять термин: глоссарий, агрегаты, инварианты, открытые вопросы |
| [coding-rules.md](coding-rules.md) | При написании кода: имена, размеры, типы, ошибки, async, FastAPI, Pydantic, SQLAlchemy, логирование |
| [testing.md](testing.md) | При написании тестов и проверке: команды, уровни тестов, фейки через dishka, имена тестов |
| [requirements-design.md](requirements-design.md) | Перед задачей по требованиям клиники (`../../docs/requirements.md`): какие контексты и слайсы, этапы, статус |
| [api-contract.md](api-contract.md) | При изменении эндпоинтов: контракт с Android-приложением и панелью, формат ошибок, совместимость |
| [ai-agent-workflow.md](ai-agent-workflow.md) | Как ставить задачи агенту и как агенту их выполнять; skills, hooks |
| [adr/](adr/) | Почему сделано именно так. Новое значимое решение — новый ADR по [шаблону](adr/0000-template.md) |

## Решения

- [ADR-0001](adr/0001-vertical-slices-and-ddd.md) — Vertical Slice Architecture + DDD (принято)
- [ADR-0002](adr/0002-tech-stack.md) — стек: FastAPI, Pydantic v2, SQLAlchemy 2 async, uv; PostgreSQL и Alembic — перед реальными пациентами (принято частично)
- [ADR-0003](adr/0003-dependency-injection-dishka.md) — внедрение зависимостей: dishka (принято)
- [ADR-0004](adr/0004-pure-domain-pydantic-at-boundary.md) — домен на dataclasses, Pydantic только на границе API (принято)
- [ADR-0005](adr/0005-transaction-per-request.md) — одна транзакция на запрос, фиксирует сценарий до ответа (принято)
- [ADR-0006](adr/0006-architecture-tests-import-linter.md) — архитектурные проверки: import-linter + pytest (принято)
- [ADR-0007](adr/0007-sqlite-for-demo.md) — SQLite и схема без миграций для демо (принято)
- [ADR-0008](adr/0008-rehab-alerts-access-contexts.md) — контексты «Реабилитация», «Оповещения», «Доступ»; контексты — слои (принято)
- [ADR-0009](adr/0009-reactions-and-periodic-checks.md) — реакция на принятые данные через порт и периодические проверки в процессе (принято, реализуется на этапе 7)
- [ADR-0010](adr/0010-rehab-plan-revisions.md) — программа реабилитации хранится версиями (принято)
- [ADR-0011](adr/0011-clinician-sign-in-argon2-sessions.md) — вход врача: Argon2id, сеанс в cookie, журнал доступа (принято)
- [ADR-0012](adr/0012-clinic-admission-enrollment-code.md) — пациент от клиники: карточка отдельно, телефон по одноразовому коду (принято)

Общие для проекта решения — в [android/docs/adr/](../../android/docs/adr/): термины
предметной области бэкенд берёт из [android/docs/domain-model.md](../../android/docs/domain-model.md).

## Источники правил

- [Claude Code: best practices](https://code.claude.com/docs/en/best-practices), [CLAUDE.md и rules](https://code.claude.com/docs/en/memory) — Anthropic
- [FastAPI: документация](https://fastapi.tiangolo.com/) — разделы Bigger Applications, Dependencies
  ([with yield](https://fastapi.tiangolo.com/tutorial/dependencies/dependencies-with-yield/)),
  Lifespan Events, Settings, Testing; [release notes](https://fastapi.tiangolo.com/release-notes/)
- [dishka: интеграция с FastAPI](https://dishka.readthedocs.io/en/stable/integrations/fastapi.html)
- [zhanymkanov/fastapi-best-practices](https://github.com/zhanymkanov/fastapi-best-practices) — практики FastAPI из продакшена
- [Architecture Patterns with Python (Cosmic Python)](https://www.cosmicpython.com/) — DDD, репозиторий, порты и адаптеры на Python
- [Pydantic v2](https://docs.pydantic.dev/latest/), [pydantic-settings](https://docs.pydantic.dev/latest/concepts/pydantic_settings/)
- [SQLAlchemy 2.0: asyncio](https://docs.sqlalchemy.org/en/20/orm/extensions/asyncio.html), [Alembic](https://alembic.sqlalchemy.org/)
- [import-linter: типы контрактов](https://import-linter.readthedocs.io/en/stable/contract_types.html)
- [Ruff: правила](https://docs.astral.sh/ruff/rules/), [mypy strict](https://mypy.readthedocs.io/en/stable/existing_code.html#introduce-stricter-options), [uv](https://docs.astral.sh/uv/)
- [RFC 9457 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [mryll/skills: vertical-slice-architecture, test-namer](https://github.com/mryll/skills),
  [booklib-ai/booklib: domain-driven-design](https://github.com/booklib-ai/booklib)
