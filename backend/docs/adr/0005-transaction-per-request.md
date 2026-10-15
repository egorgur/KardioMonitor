# ADR-0005: Одна транзакция на запрос, фиксирует сценарий до ответа

- Статус: принято (уточнено с переходом на dishka, ADR-0003)
- Дата: 2026-10-16

## Контекст

Телефон считает пакет минут принятым, когда получил `200`, и больше его не присылает.
Если сервер ответит `200`, а фиксация после этого не удастся, данные потеряются.

И FastAPI (код после `yield` в зависимости по умолчанию —
[после ответа](https://fastapi.tiangolo.com/tutorial/dependencies/dependencies-with-yield/)),
и dishka (`ContainerMiddleware` закрывает REQUEST-scope после `await app(...)`, то есть после
отправки ответа — проверено по исходникам dishka 1.10) завершают зависимости **после** ответа.
Поэтому `commit` в финализаторе сессии ненадёжен.

## Решение

- Сессия — одна на запрос (`Scope.REQUEST` в `platform/db/provider.py`); её закрытие
  **откатывает** всё незафиксированное.
- Фиксация — явная: сценарий получает `Transaction` (`Protocol` из `shared_kernel` с
  `async def commit()`; dishka отдаёт под ним ту же `AsyncSession`) и вызывает
  `await transaction.commit()` **до возврата**:

```python
async def upload_minutes(patient_id, batch, *, history, transaction) -> UploadReceipt:
    outcome = await history.save(patient_id, batch)
    last_minute = await history.last_minute_of(patient_id) or batch.last_started_at
    await transaction.commit()
    return UploadReceipt(len(batch.minutes), outcome.new_minutes, last_minute)
```

- Ошибка фиксации поднимается до ответа → `500`, телефон повторит загрузку.
- Репозитории `commit()` не вызывают; сценарий без записи `Transaction` не получает.
- Исключение до `commit` (включая `DomainRuleViolation`) — ничего не сохраняется.

## Последствия

- Запрос атомарен: пакет минут сохраняется целиком или не сохраняется.
- Идемпотентная загрузка + фиксация до ответа = телефон может повторять запрос без потерь
  и дублей.
- Тест сценария проверяет фиксацию фейком `FakeTransaction` (`commits == 1`), а отказ —
  `commits == 0`.
- Забытый `commit` — тихая потеря данных; ловится тестами слайса (данные не видны) и ревью.

## Отвергнутые варианты

- `commit` в финализаторе сессии — выполняется после ответа (и в FastAPI `Depends`, и в dishka).
- FastAPI `Depends(..., scope="function")` — закрывается до ответа, но несовместим с
  dishka-провайдерами; после перехода на dishka был бы второй механизм управления сессией.
- Порт Unit of Work с репозиториями внутри — лишний слой, пока один запрос = одна транзакция.
