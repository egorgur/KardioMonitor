# Контракт HTTP API

Знание о контракте между сервером и его клиентами, которое нельзя безопасно менять «по
ходу». Клиенты: Android-приложение на телефоне пациента и панель клиники (`../frontend`).
Приложение нельзя обновить на всех телефонах одновременно: **старые версии продолжают
работать с новым сервером**. Поэтому контракт меняется только по правилам этого документа.

Источник истины о полях — OpenAPI, который генерирует FastAPI (`/openapi.json`, Swagger UI —
`/docs`). Зафиксированный снимок — `tests/contract/openapi.json`. Клиент на Android —
`android/.../features/clinic/data/ClinicJson.kt` (тест `ClinicJsonTest`).

## Общие правила

| Правило | Значение |
|---|---|
| Версия | В пути: `/api/v1/...`. Служебное: `/health` — без версии |
| Формат | JSON, UTF-8. Имена полей — `snake_case` |
| Время в запросах | ISO 8601 **с часовым поясом**: `2026-10-15T08:31:00+07:00` или `…Z`. Без пояса — `422` |
| Время в ответах | Всегда UTC с `Z` (`UtcDatetime` в `platform.web`) |
| Дата дня | `YYYY-MM-DD`, день **в часовом поясе пациента** |
| Идентификаторы | UUID строкой |
| Телефон | `Authorization: Bearer <токен загрузки>` (выдаётся при регистрации) |
| Панель | `X-Dashboard-Key: <ключ>`, если на сервере задан `KARDIO_DASHBOARD_KEY`; иначе без заголовка |
| Трассировка | Каждый ответ содержит `X-Request-ID`; клиент может прислать свой |
| Неизвестные поля | В запросе — `422` (`extra="forbid"`): сервер обновляется раньше приложения. В ответе клиент **обязан игнорировать** незнакомые поля |
| Отсутствие значения | `null` (`heart_rate: null` — замера нет), а не пропуск поля и не `0` |
| ngrok | Телефон шлёт `ngrok-skip-browser-warning: 1` — иначе бесплатный план может вернуть HTML-предупреждение |

## Ошибки — RFC 9457 (`application/problem+json`)

```json
{
  "type": "/problems/minute-batch-too-large",
  "title": "Нарушено правило данных",
  "status": 422,
  "detail": "в пакете 12000 минут, допустимо не больше 10080",
  "code": "minute_batch_too_large",
  "request_id": "6f1c…"
}
```

- `code` — стабильный машинный код; клиент решает, что делать, **по `code`**. Коды не переименовываются.
- `detail` — по-русски, для журнала и экрана; не содержит данных пациента.
- Ошибки валидации Pydantic — тоже в этом формате: `code: "invalid_request"` и поле `errors`
  (`loc`, `msg`, `type`).

| Статус | Когда | `code` |
|---|---|---|
| 401 | Телефон: нет токена или сервер его не знает | `unauthorized` |
| 401 | Панель: нет или неверный ключ панели | `dashboard_key_required` |
| 404 | Пациент не найден | `patient_not_found` |
| 422 | Неверный формат запроса | `invalid_request` |
| 422 | Нарушено правило предметной области | `heart_rate_out_of_range`, `minute_not_aligned`, `duplicate_minute`, `empty_minute_batch`, `minute_batch_too_large`, `naive_datetime`, `invalid_patient_name`, `unknown_time_zone`, `kind_code_out_of_range`, `negative_steps`, `intensity_out_of_range` |
| 500 | Ошибка сервера. Трассировка не раскрывается | `internal_error` |

Клиент повторяет запрос при сетевой ошибке и `5xx`; при `4xx` — не повторяет. На `401`
телефон предлагает зарегистрироваться заново (сервер мог быть пересоздан).

## Телефон (v1)

### `POST /api/v1/register` — регистрация

Слайс `patients/registration`. Без авторизации.

```json
{ "name": "Иван П.", "time_zone": "Asia/Novosibirsk" }
```

`name` — 1–100 знаков; `time_zone` — IANA-имя часового пояса телефона (по нему считаются
дни пациента). Ответ `201`; **токен показывается один раз**, сервер хранит только его отпечаток:

```json
{ "patient_id": "72f4…", "name": "Иван П.", "time_zone": "Asia/Novosibirsk", "token": "…" }
```

### `GET /api/v1/me` — проверка токена

`200 { "patient_id", "name", "time_zone" }` или `401`.

### `POST /api/v1/me/activity-minutes` — принять минуты активности

Слайс `monitoring/minute_upload`. **Идемпотентно**: повтор того же пакета ничего не меняет,
повтор минуты с другими данными перезаписывает её. Транзакция фиксируется до ответа:
получив `200`, телефон может не повторять пакет.

```json
{
  "minutes": [
    { "started_at": "2026-10-15T03:01:00Z", "steps": 54, "heart_rate": 92,
      "kind": "other", "kind_code": 80, "intensity": 37 },
    { "started_at": "2026-10-15T03:02:00Z", "steps": 0, "heart_rate": null,
      "kind": "not_worn", "kind_code": null, "intensity": 0 }
  ]
}
```

| Поле | Тип | Правила |
|---|---|---|
| `started_at` | время с поясом | Секунды и доли = 0; уникально в пакете |
| `steps` | int | ≥ 0 |
| `heart_rate` | int \| null | 11–249 или `null` (замера нет) |
| `kind` | `not_worn` \| `charging` \| `sleep` \| `other` | Расшифровано телефоном |
| `kind_code` | int \| null | 0–255 — исходный код браслета для `other` (не расшифрован); для остальных `null`. Необязательное |
| `intensity` | int | 0–255, сырое значение браслета |

Пакет: 1–10 080 минут (7 суток); телефон шлёт по 1 440. Ответ `200`:

```json
{ "received": 30, "new": 28, "last_minute_started_at": "2026-10-15T03:31:00Z" }
```

### `GET /api/v1/me/activity-minutes/last` — откуда продолжать

Слайс `monitoring/upload_cursor`. `200 { "started_at": "…Z" }` или `{ "started_at": null }`,
если минут пациента ещё нет. Телефон отправляет минуты, начавшиеся позже.

### `POST /api/v1/me/live-heart-rate` — значение «пульса сейчас»

Слайс `monitoring/live_heart_rate`. Тело: `{ "measured_at": "…Z", "heart_rate": 88 }`
(11–249). Идемпотентно по `measured_at`. Ответ `204`. Телефон шлёт значения, пока идёт
измерение (раз в 2–3 с); пропуски при плохой сети допустимы.

## Панель клиники (v1)

Защищены ключом панели, если он задан (это защита демо, а не вход сотрудников — см.
открытые вопросы в [domain-model.md](domain-model.md)).

| Метод и путь | Слайс | Ответ |
|---|---|---|
| `GET /api/v1/patients` | `monitoring/patient_overview` | `[{patient_id, name, time_zone, registered_at, last_minute_started_at, live_heart_rate, live_heart_rate_measured_at}]` |
| `GET /api/v1/patients/{patient_id}/days/{day}` | `monitoring/patient_day` | `{day, time_zone, summary: {recorded_minutes, steps, average_heart_rate, min_heart_rate, max_heart_rate, heart_rate_coverage_percent, not_worn_minutes}, minutes: [{started_at, steps, heart_rate, kind}]}` |
| `GET /api/v1/patients/{patient_id}/live-heart-rate?window_seconds=300` | `monitoring/live_heart_rate` | `{window_seconds, readings: [{measured_at, heart_rate}]}`; окно 10–3600 с |

## Служебное

| Путь | Назначение |
|---|---|
| `GET /health` | Процесс жив: `200 {"status": "ok"}` |
| `GET /`, `/assets/…` | Собранная панель (`KARDIO_FRONTEND_DIST`), если она есть |
| `GET /docs`, `/openapi.json` | Swagger UI и схема |

## Планируется

- `POST /api/v1/me/battery-readings` — заряд браслета (`patients/battery_report`).
- Вход сотрудников клиники вместо ключа панели (контекст «Доступ»).

## Правила изменения контракта

Внутри `v1` разрешено (**совместимые** изменения):

- добавить новый эндпоинт;
- добавить **необязательное** поле в запрос (со значением по умолчанию);
- добавить поле в ответ;
- добавить новый `code` ошибки для новой ситуации;
- ослабить ограничение запроса (увеличить лимит).

Запрещено внутри `v1` (**несовместимые** — только новой версией `v2` рядом с `v1`):

- удалить или переименовать поле, эндпоинт, `code`;
- сделать поле запроса обязательным, изменить тип или смысл поля;
- ужесточить ограничение (уменьшить лимит пакета);
- изменить семантику идемпотентности или статус-коды успешного ответа.

Порядок любого изменения контракта:

1. Изменение описано здесь (таблица полей, пример).
2. Схемы в слайсе + тест слайса; на стороне Android — `ClinicJson` и `ClinicJsonTest`.
3. Снимок обновлён командой
   `uv run python -m kardiomonitor.app.export_openapi > tests/contract/openapi.json`
   и **дифф снимка прочитан**: в нём нет непредусмотренных изменений.
4. Несовместимое изменение или вывод `v1` из работы — только с ADR и согласием человека,
   с планом обновления Android-приложения.
