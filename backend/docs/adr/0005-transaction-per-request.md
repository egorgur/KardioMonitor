# ADR-0005: Одна транзакция на запрос, фиксация до отправки ответа

- Статус: предложено
- Дата: 2026-10-16

## Контекст

Телефон считает пакет минут принятым, когда получил `200`, и больше его не присылает.
Если сервер ответит `200`, а фиксация транзакции после этого не удастся, данные потеряются.
По умолчанию FastAPI выполняет код после `yield` в зависимости **после отправки ответа**
(`scope="request"`, [документация](https://fastapi.tiangolo.com/tutorial/dependencies/dependencies-with-yield/)),
поэтому `commit` там ненадёжен.

## Решение

- Сессия на запрос — зависимость `DbSession` в `platform/db` с `scope="function"`:

```python
async def transactional_session(request: Request) -> AsyncIterator[AsyncSession]:
    sessionmaker: async_sessionmaker[AsyncSession] = request.app.state.sessionmaker
    async with sessionmaker() as session, session.begin():
        yield session   # commit — при выходе без исключения, rollback — при исключении

DbSession = Annotated[AsyncSession, Depends(transactional_session, scope="function")]
```

- `scope="function"` закрывает зависимость после эндпоинта, но **до** отправки ответа:
  ошибка фиксации превращается в `500`, и телефон повторит загрузку.
- Репозитории и сценарии не вызывают `commit()` и `rollback()`.
- Исключение в эндпоинте (включая `DomainRuleViolation` и `HTTPException`) откатывает
  транзакцию.
- Требует FastAPI ≥ 0.121.1: `scope` появился в 0.121.0, в 0.121.1 исправлен
  `scope="function"` для зависимостей без параметров. Нижняя граница — в `pyproject.toml`.

## Последствия

- Запрос атомарен: пакет минут сохраняется целиком или не сохраняется.
- Идемпотентная загрузка + фиксация до ответа = телефон может повторять запрос без
  потерь и дублей.
- Работа после ответа (`BackgroundTasks`) не может пользоваться сессией запроса.

## Отвергнутые варианты

- `commit` после `yield` со `scope="request"` — фиксация после ответа, возможна потеря данных.
- Явный `await session.commit()` в каждом эндпоинте — легко забыть; слайсу пришлось бы
  знать о SQLAlchemy.
- Порт Unit of Work в домене — лишняя абстракция, пока один запрос = одна транзакция.
