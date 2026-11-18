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
| Панель | Cookie сеанса врача `kardio_session` (ставит `POST /api/v1/auth/sign-in`, ТР-11, ADR-0011). Врач видит только своих пациентов |
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
| 401 | Панель: нет сеанса врача или он истёк | `session_required` |
| 401 | Вход: неверный логин или пароль (одинаково для обоих) | `invalid_credentials` |
| 429 | Вход: 5 неудач подряд — вход закрыт на 15 минут | `sign_in_locked` |
| 404 | Пациент не найден **или он пациент другого врача** — ответ одинаковый | `patient_not_found` |
| 404 | Оповещения нет у этого пациента | `alert_not_found` |
| 422 | Неверный формат запроса | `invalid_request` |
| 422 | Нарушено правило предметной области | `heart_rate_out_of_range`, `minute_not_aligned`, `duplicate_minute`, `empty_minute_batch`, `minute_batch_too_large`, `naive_datetime`, `invalid_patient_name`, `unknown_time_zone`, `kind_code_out_of_range`, `negative_steps`, `intensity_out_of_range`, `invalid_training_zone`, `invalid_daily_targets`, `invalid_rehab_stage`, `invalid_program_duration`, `invalid_program_stages`, `invalid_monitoring_settings`, `invalid_full_name`, `invalid_birth_date`, `invalid_body_parameters`, `invalid_enrollment_code` |
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

### `POST /api/v1/enroll` — привязка телефона по коду из QR v2

Слайс `patients/enrollment` (ТР-07, п. 4). Без авторизации: код — одноразовый секрет. Телефон
читает `e=<код>` из QR v2 ([android/docs/qr-contract.md](../../android/docs/qr-contract.md)) и
получает токен загрузки пациента, которого создала клиника, — без ввода имени.

```json
{ "code": "Xq3…" }
```

Ответ `201` — как у `/register`: `{patient_id, name, time_zone, token}`; токен показывается один
раз. Код неизвестен, уже использован или истёк (72 ч) — `422 invalid_enrollment_code`. Повторная
привязка новым кодом заменяет токен: прежний телефон получает `401`.

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

### `GET /api/v1/me/live-heart-rate-request` — активный запрос клиники

Слайс `monitoring/live_heart_rate_request` (ТР-09). Телефон вызывает после каждой попытки
синхронизации. Ответ `200`:

```json
{
  "request": {
    "request_id": "…", "duration_minutes": 10,
    "requested_at": "2026-11-01T09:00:00Z", "expires_at": "2026-11-01T11:00:00Z",
    "status": "waiting_for_phone", "started_at": null, "finished_at": null,
    "failure_reason": null
  }
}
```

`request: null`, если активного запроса нет. Активный — `waiting_for_phone` с ещё не
наступившим `expires_at` или `running`. Просроченный ожидающий запрос атомарно становится
`failed` / `expired` и телефону не отдаётся.

### `PUT /api/v1/me/live-heart-rate-requests/{request_id}/status` — состояние запроса

Идемпотентный отчёт телефона. Тело:

```json
{ "status": "running", "failure_reason": null }
```

Телефон передаёт только `running`, `completed`, `stopped_by_patient` или `failed`.
`failure_reason` обязателен только для `failed`: `expired`, `band_unavailable`,
`notification_permission_denied`, `companion_association_missing` или `measurement_failed`.
Ответ `200` — запрос в том же формате, что объект выше.

- `running` переводит ожидающий запрос в работу и фиксирует `started_at`;
- `completed` и `stopped_by_patient` допустимы после `running` и фиксируют `finished_at`;
- `failed` допустим из ожидания и работы;
- повтор того же отчёта ничего не меняет; обратный или несовместимый переход —
  `409 invalid_live_heart_rate_request_transition`;
- чужой или неизвестный запрос — `404 live_heart_rate_request_not_found`.

### `GET /api/v1/me/rehab-plan` — программа и нормы для телефона

Слайс `rehab/targets_for_phone` (ТР-01, п. 9). Телефон забирает при синхронизации и по нормам
сам считает выполнение нормы дня (ТР-03) — те же правила, что на сервере. `200`:

```json
{
  "time_zone": "Europe/Moscow",
  "effective_from": "2026-10-12",
  "walking_steps_threshold": 60,
  "program": {
    "start_date": "2026-10-01",
    "duration_days": 42,
    "stages": [
      { "first_day": 1, "last_day": 42,
        "targets": { "training_zone": { "lower_bpm": 90, "upper_bpm": 110 },
                     "training_minutes": 30, "steps": 10000 } }
    ]
  }
}
```

Текущая версия плана; нормы дня — этап, в который попадает день (день 1 — `start_date`, дни — по
`time_zone`). Пороги оповещений и время проверок телефону не передаются.

### `GET /api/v1/me/alerts?after=0` — новые оповещения пациента

Слайс `alerts/patient_alerts` (ТР-08, п. 10). Телефон забирает при синхронизации и показывает
системным уведомлением (канал «Оповещения клиники») и списком. `after` — `cursor` прошлого ответа,
в первый раз `0`. `200`:

```json
{
  "alerts": [
    { "alert_id": "…", "kind": "daily_target_missed", "day": "2026-10-12",
      "created_at": "2026-10-12T17:00:00Z",
      "high_heart_rate": null,
      "daily_target": { "training_minutes": 12, "target_training_minutes": 30,
                        "steps": 6200, "target_steps": 10000 },
      "low_activity": null, "training_minutes_exceeded": null }
  ],
  "cursor": 17,
  "has_more": false
}
```

- Не больше 100 за раз; `has_more: true` — есть следующая поддерживаемая запись,
  запросить ещё раз с новым `cursor`.
- Необязательный повторяемый `supported_kinds` — список `AlertKind`, например
  `?supported_kinds=high_heart_rate&supported_kinds=training_minutes_exceeded`.
  **Без параметра — только четыре прежних вида**, без `training_minutes_exceeded`.
  Новый телефон передаёт все пять известных видов. Неизвестное значение — `422`.
  Фильтр применяется до пагинации; `cursor` — позиция последней возвращённой записи,
  при пустом ответе сохраняется входной `after`.
- Оповещение приходит **снова с тем же `alert_id`**, если изменилось (эпизод высокого пульса
  продлился следующим пакетом минут): телефон обновляет запись и второй раз уведомление не
  показывает (п. 10).
- Значения оповещения — как у панели (ниже), без `patient_id` и отметки врача. Текстов сервер не
  присылает: тексты пациенту утверждает врач (В-19), их собирает приложение.

## Вход врача (v1)

Слайс `access/sign_in` (ТР-11, [ADR-0011](adr/0011-clinician-sign-in-argon2-sessions.md)).
Аккаунты создаёт администратор: `uv run python -m kardiomonitor.app.admin create-clinician`.

| Метод и путь | Тело | Ответ |
|---|---|---|
| `POST /api/v1/auth/sign-in` | `{login, password}` | `200 {clinician_id, login, name}` и `Set-Cookie: kardio_session=…; HttpOnly; Secure; SameSite=Strict; Path=/api`. `401 invalid_credentials`, `429 sign_in_locked` |
| `POST /api/v1/auth/sign-out` | — | `204`, сеанс завершён на сервере, cookie снята. Без сеанса — тоже `204` |
| `GET /api/v1/auth/me` | — | `200 {clinician_id, login, name}` или `401 session_required` |

Логин — без учёта регистра и пробелов по краям. Сеанс истекает через 8 ч без запросов.

## Панель клиники (v1)

Все эндпоинты — с cookie сеанса врача; без неё `401 session_required`. Пациент другого врача
отвечает `404 patient_not_found`, как несуществующий. `GET /api/v1/patients` — пациенты
вошедшего врача. Открытие и правка плана реабилитации записываются в журнал доступа.

> Ключ панели (`X-Dashboard-Key`, код `dashboard_key_required`) удалён с ТР-11: требование
> запрещает доступ к данным по общему ключу. Это несовместимое изменение только для панели —
> она собирается и раздаётся вместе с сервером и обновляется одновременно с ним; телефона оно
> не касается.

| Метод и путь | Слайс | Ответ |
|---|---|---|
| `GET /api/v1/patients?search=` | `monitoring/patient_overview` (пациенты врача; `search` — подстрока ФИО без учёта регистра и `ё`/`е`) | `[{patient_id, name, time_zone, registered_at, last_minute_started_at, live_heart_rate, live_heart_rate_measured_at}]` |
| `GET /api/v1/patients/{patient_id}/days/{day}` | `monitoring/patient_day` | `{day, time_zone, summary: {recorded_minutes, steps, average_heart_rate, min_heart_rate, max_heart_rate, heart_rate_coverage_percent, not_worn_minutes}, heart_rate_distribution: [{from_bpm, to_bpm, minutes}], minutes: [{started_at, steps, heart_rate, kind}]}` |
| `POST /api/v1/patients` | `patients/admission` | Создать пациента — см. ниже |
| `POST /api/v1/patients/{patient_id}/enrollment-code` | `patients/admission` | Новый код привязки телефона (прежний недействителен) |
| `GET /api/v1/patients/{patient_id}/profile` | `patients/patient_card` | Карточка пациента — см. ниже |
| `GET /api/v1/rehab-progress` | `rehab/progress_overview` | Прогресс и норма сегодня по всем пациентам врача — см. ниже |
| `GET /api/v1/patients/{patient_id}/rehab-plan` | `rehab/rehab_plan` | План реабилитации — см. ниже |
| `PUT /api/v1/patients/{patient_id}/rehab-plan` | `rehab/rehab_plan` | Изменить программу и параметры наблюдения — см. ниже |
| `GET /api/v1/patients/{patient_id}/days/{day}/target-progress` | `rehab/day_progress` | Выполнение нормы дня — см. ниже |
| `GET /api/v1/patients/{patient_id}/rehab-progress` | `rehab/rehab_progress` | Прогресс реабилитации — см. ниже |
| `GET /api/v1/patients/{patient_id}/live-heart-rate?window_seconds=300` | `monitoring/live_heart_rate` | `{window_seconds, readings: [{measured_at, heart_rate}]}`; окно 10–3600 с |
| `POST /api/v1/patients/{patient_id}/live-heart-rate-requests` | `monitoring/live_heart_rate_request` | Создать запрос: `{duration_minutes}` (1–60, по умолчанию 10) → `201` и запрос. Уже есть ожидающий/идущий → `409 active_live_heart_rate_request` |
| `GET /api/v1/patients/{patient_id}/live-heart-rate-requests/latest` | `monitoring/live_heart_rate_request` | `{request: <последний запрос или null>}`; при чтении просроченное ожидание становится `failed` / `expired` |
| `GET /api/v1/alerts?kind=&limit=100` | `alerts/clinician_alerts` | Оповещения всех пациентов врача — см. ниже |
| `GET /api/v1/patients/{patient_id}/alerts?kind=&limit=100` | `alerts/clinician_alerts` | Оповещения пациента — см. ниже |
| `POST /api/v1/patients/{patient_id}/alerts/{alert_id}/viewed` | `alerts/clinician_alerts` | Отметка «просмотрено»: `204`; идемпотентно. Нет такого оповещения у пациента — `404 alert_not_found` |
| `GET /api/v1/alerts/unviewed-counts` | `alerts/unviewed_counts` | `[{patient_id, unviewed_alerts}]` — строка на каждого пациента врача, для списка на главной (ТР-06) |

### Пульс по запросу клиники (ТР-09)

Объект запроса:

```json
{
  "request_id": "…", "patient_id": "…", "duration_minutes": 10,
  "requested_at": "2026-11-01T09:00:00Z", "expires_at": "2026-11-01T11:00:00Z",
  "status": "waiting_for_phone", "started_at": null, "finished_at": null,
  "failure_reason": null
}
```

В ответе телефону `patient_id` не передаётся. Срок ожидания — 2 часа. У пациента одновременно
не больше одного нетерминального запроса. Состояния: `waiting_for_phone`, `running`,
`completed`, `stopped_by_patient`, `failed`. Терминальные запросы не меняются. Новый запрос
можно создать после терминального или просроченного; история сохраняется. Врач получает пациента
только через `AttendedPatients.require`.

### Распределение пульса (ТР-04)

`heart_rate_distribution` в ответе дня — 10 интервалов по порядку: `{from_bpm: null, to_bpm: 39}`,
`{40, 49}`, …, `{110, 119}`, `{from_bpm: 120, to_bpm: null}`; границы включительно, `minutes` —
минут с пульсом в интервале. Сумма `minutes` = число минут дня с пульсом.

### Создание пациента и карточка (ТР-07)

`POST /api/v1/patients` — врач создаёт пациента и становится его лечащим врачом:

```json
{
  "last_name": "Иванов", "first_name": "Пётр", "middle_name": null,
  "birth_date": "1961-04-12", "height_cm": 176, "weight_kg": 82.5,
  "diagnosis": "текст", "time_zone": "Europe/Moscow"
}
```

| Поле | Правила | `code` |
|---|---|---|
| `last_name`, `first_name` | 1–32 знака, обязательно | `invalid_full_name` («укажите фамилию») |
| `middle_name` | `null` или 1–32 знака | `invalid_full_name` |
| `birth_date` | не в будущем, возраст 18–120 лет | `invalid_birth_date` |
| `height_cm`, `weight_kg` | 100–250 см; 30–300 кг | `invalid_body_parameters` |
| `diagnosis` | 1–1000 знаков, свободный текст (В-06) | `invalid_body_parameters` |
| `time_zone` | IANA-имя: по нему считаются дни пациента | `unknown_time_zone` |

Ответ `201`:

```json
{ "patient_id": "…", "name": "Иванов Пётр", "enrollment_code": "Xq3…",
  "enrollment_code_expires_at": "…Z" }
```

`name` — ФИО через пробел. Программа — по умолчанию (ТР-01, п. 5); изменить её —
`PUT …/rehab-plan`. `enrollment_code` — одноразовый, 72 ч: утилита персонала кладёт его в QR v2
(`e=`), телефон обменивает на токен (`POST /api/v1/enroll`). Код показывается один раз; потерян —
`POST …/enrollment-code` (`201 {enrollment_code, enrollment_code_expires_at}`, прежний код
недействителен).

`GET /api/v1/patients/{patient_id}/profile` — карточка, `200`:
`{patient_id, name, time_zone, registered_at, profile}`, где `profile` — `{last_name, first_name,
middle_name, birth_date, age_years, height_cm, weight_kg, diagnosis}` или `null` (пациент
зарегистрировался с телефона по имени). Открытие карточки записывается в журнал доступа. ФИО,
дата рождения и диагноз — только лечащему врачу; в журналы сервера не пишутся.

### Прогресс всех пациентов врача (ТР-06)

`GET /api/v1/rehab-progress`, `200` — строка на каждого пациента врача, для списка на главной:

```json
[
  { "patient_id": "…", "program_day": 12, "duration_days": 42, "is_finished": false,
    "met_days": 9, "completed_days": 11,
    "today": { "training_minutes": 15, "target_training_minutes": 30,
               "steps": 6200, "target_steps": 10000, "is_met": false } }
]
```

`today` — `null` вне программы. Панель соединяет строки с `GET /api/v1/patients` и
`GET /api/v1/alerts/unviewed-counts` по `patient_id`. Считается по кешу итогов дня (ТР-06, п. 4):
500 пациентов — около 0,5 с на машине разработчика.

### Оповещения (ТР-08)

Создаёт сервер: «высокий пульс» — при приёме минут и «пульса сейчас», плановые дневные — в
`daily_alert_time_utc` пациента (раз в минуту, ADR-0009). `training_minutes_exceeded` — сразу
после сохранения минут и при правке плана (сегодня пациента), независимо от планировщика.
Не удаляются. Приходят с задержкой
синхронизации телефона (до 30–60 мин) и не заменяют экстренную помощь — это панель и приложение
пишут рядом со списком (п. 11).

```json
{
  "alert_id": "…", "patient_id": "…", "kind": "high_heart_rate", "day": null,
  "created_at": "2026-10-12T07:20:00Z", "is_viewed": false, "viewed_at": null,
  "high_heart_rate": { "started_at": "2026-10-12T07:01:00Z", "last_at": "2026-10-12T07:06:00Z",
                       "duration_minutes": 6, "max_heart_rate": 140, "threshold_bpm": 130 },
  "daily_target": null,
  "low_activity": null,
  "training_minutes_exceeded": null
}
```

| `kind` | Когда | Значения |
|---|---|---|
| `high_heart_rate` | Пульс выше `high_heart_rate_threshold`; замеры без перерыва больше 5 мин — один эпизод (В-15) | `high_heart_rate`: начало и последний замер эпизода, минут, максимум, порог. `day` — `null`: момент — `started_at` |
| `daily_target_missed`, `daily_target_met` | Во время дневных оповещений — по данным на этот момент; одно на день | `daily_target`: тренировочных минут и шагов — факт и норма дня. `day` — оцениваемый день пациента |
| `low_activity` | Тогда же, если шагов за день меньше `low_activity_steps`; вместе с `daily_target_missed` (В-16) | `low_activity`: шагов и порог |
| `training_minutes_exceeded` | Полный итог дня строго больше индивидуального верхнего порога; только дни программы своей версии | `training_minutes_exceeded: {training_minutes: int, threshold_minutes: int}`; `day` обязателен |

- Заполнено поле значений своего вида, остальные — `null`. `day` — день в поясе пациента;
  моменты — UTC, панель показывает их в поясе пациента (`time_zone` из `GET /patients`).
- Новые сверху (`created_at`). `kind` — только этот вид; `limit` — 1–500.
- Повтор тех же минут оповещений не создаёт. Эпизод, продолжившийся в следующем пакете минут,
  меняет своё оповещение (не создаёт второе) и снова делает его непросмотренным.
- Плановое дневное оповещение за день создаётся один раз; поздние минуты дня его не меняют (В-16).
  Только для дней программы; «высокий пульс» — всегда, пока приходят данные (В-01).
- `training_minutes_exceeded` присутствует nullable-полем во всех ответах оповещений врача и
  телефона (у других видов — `null`). Новый вид фиксирует фактические минуты и порог первого
  обнаружения: одно на `(patient_id, kind, day)` навсегда. Рост, повторы и исправления не
  меняют запись, `viewed_at` и курсор, не удаляют и не создают повторно. Поздние и исправленные
  минуты могут впервые создать превышение; расчёт по версии дня в поясе пациента, не по live.
  Утреннее превышение не блокирует плановые дневные оповещения и выполнение нижней нормы.

### План реабилитации (ТР-01)

`GET /api/v1/patients/{patient_id}/rehab-plan` — текущая версия плана, `200`:

```json
{
  "time_zone": "Europe/Moscow",
  "is_default": true,
  "effective_from": "2026-10-01",
  "program": {
    "start_date": "2026-10-01",
    "duration_days": 42,
    "stages": [
      { "first_day": 1, "last_day": 42,
        "targets": { "training_zone": { "lower_bpm": 90, "upper_bpm": 110 },
                     "training_minutes": 30, "steps": 10000 } }
    ]
  },
  "monitoring_settings": {
    "walking_steps_threshold": 60,
    "high_heart_rate_threshold": 130,
    "low_activity_steps": 1000,
    "daily_alert_time_utc": "17:00:00",
    "training_minutes_upper_threshold": null
  }
}
```

- `is_default` — план не сохранялся: программа по умолчанию с дня регистрации пациента.
- `effective_from` — день пациента, с которого действует эта версия (ADR-0010).
- `daily_alert_time_utc` — время дневных оповещений в UTC (`HH:MM:SS`); панель показывает его и
  в поясе пациента (`time_zone`).

`PUT /api/v1/patients/{patient_id}/rehab-plan` — тело `{program, monitoring_settings}` в том же
виде; ответ `200` — как у `GET`. Правка действует **с сегодняшнего дня пациента**; прошлые дни
оцениваются прежними нормами; вторая правка за день заменяет первую. Идемпотентно.
`monitoring_settings.training_minutes_upper_threshold` — целое 1–1440 минут или `null`
(отключено, по умолчанию). В PUT **отсутствие поля сохраняет текущий порог**, явный `null`
отключает. Строки, дробные числа и boolean не принимаются (`invalid_request`); целое вне
диапазона — `invalid_monitoring_settings`. Порог не зависит от целевой нормы. Сразу после
сохранения плана сегодняшний полный итог проверяется в той же транзакции; ошибка реакции
откатывает план. Минуты и новое оповещение при upload также фиксируются одной транзакцией.

| Поле | Правила | `code` при нарушении |
|---|---|---|
| `duration_days` | 1–365 | `invalid_program_duration` |
| `stages` | Подряд от дня 1 до `duration_days`, без пропусков и пересечений | `invalid_program_stages` |
| `first_day`, `last_day` | `1 ≤ first_day ≤ last_day` | `invalid_rehab_stage` |
| `training_zone` | `40 ≤ lower_bpm < upper_bpm ≤ 220` | `invalid_training_zone` |
| `training_minutes`, `steps` | 1–1440; 0–100 000 | `invalid_daily_targets` |
| `monitoring_settings` | порог ходьбы 1–250, высокий пульс 60–250, «почти не ходит» 0–100 000, верхний порог минут 1–1440 или `null` | `invalid_monitoring_settings` |

### Выполнение нормы дня (ТР-02, ТР-03)

`GET /api/v1/patients/{patient_id}/days/{day}/target-progress`, `200`:

```json
{
  "day": "2026-10-12",
  "program_day": 12,
  "progress": {
    "targets": { "training_zone": { "lower_bpm": 90, "upper_bpm": 110 },
                 "training_minutes": 30, "steps": 10000 },
    "training_minutes": 15,
    "steps": 6200,
    "walking_minutes": 41,
    "walking_minutes_without_heart_rate": 3,
    "has_data": true,
    "is_final": false,
    "is_met": false
  }
}
```

Нормы — версии плана, действовавшей в этот день. `is_final: false` — сутки пациента не
закончились («пока»). `walking_minutes_without_heart_rate` > 0 — тренировочных минут может быть
больше, чем видно. День вне программы — `program_day: null, progress: null`.

### Прогресс реабилитации (ТР-05)

`GET /api/v1/patients/{patient_id}/rehab-progress`, `200`:

```json
{
  "start_date": "2026-10-01",
  "duration_days": 42,
  "program_day": 12,
  "is_finished": false,
  "completed_days": 11,
  "met_days": 9,
  "current_stage": { "number": 1, "first_day": 1, "last_day": 15 },
  "days": [
    { "day": "2026-10-01", "program_day": 1, "status": "met" },
    { "day": "2026-10-12", "program_day": 12, "status": "in_progress" }
  ]
}
```

- `program_day` — «день X из `duration_days`»; `null` — программа ещё не началась. После
  последнего дня — `duration_days` и `is_finished: true`.
- `met_days` из `completed_days` — только завершённые сутки; сегодня — `in_progress`.
- `status`: `met`, `not_met`, `no_data` (минут нет — норма не выполнена), `in_progress`.
- `current_stage` — `null`, если программа не идёт.

## Служебное

| Путь | Назначение |
|---|---|
| `GET /health` | Процесс жив: `200 {"status": "ok"}` |
| `GET /`, `/assets/…` | Собранная панель (`KARDIO_FRONTEND_DIST`), если она есть |
| `GET /docs`, `/openapi.json` | Swagger UI и схема |

## Планируется

- `POST /api/v1/me/battery-readings` — заряд браслета (`patients/battery_report`).

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
