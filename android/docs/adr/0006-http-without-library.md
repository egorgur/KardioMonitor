# ADR-0006: Обмен с сервером — HttpURLConnection и org.json без библиотек

- Статус: принято (демо-прототип; пересмотреть, если запросов станет больше трёх-четырёх видов)
- Дата: 2026-10-16

## Контекст

Для демонстрации приложение отправляет данные браслета на сервер клиники (`../backend`,
доступ через ngrok): регистрация, пакеты минут, «пульс сейчас». Запросов четыре, все — JSON
поверх HTTPS. Каждая библиотека (Retrofit, OkHttp, Ktor, kotlinx.serialization) — то, что
агенту нужно знать, обновлять и согласовывать с AGP и Kotlin (ADR-0004 исходил из того же).

## Решение

- HTTP — `platform.http.JsonHttpClient` на `HttpURLConnection` (есть в Android): таймауты,
  `Authorization: Bearer`, заголовок `ngrok-skip-browser-warning`, ответ `problem+json` →
  `HttpProblemException : IOException`. Работа — в `Dispatchers.IO`.
- JSON — `org.json` (есть в Android). Перевод в контракт API — `clinic.data.ClinicJson`.
- В JVM-тестах классы `org.json` из android.jar — заглушки (`isReturnDefaultValues = true`
  возвращает пустые значения), поэтому добавлена **тестовая** зависимость
  `testImplementation("org.json:json:20260814")`: `ClinicJsonTest` проверяет JSON по
  контракту сервера. В APK она не попадает.
- Разрешение `INTERNET` в манифесте. Только HTTPS (ngrok) — cleartext не разрешён.

## Последствия

- Новых runtime-зависимостей нет; сетевой код — один файл, понятный без документации библиотеки.
- Ручная работа с JSON: поле, которого нет в контракте, ловит только `ClinicJsonTest`.
  При изменении API — обновлять `ClinicJson` и тест вместе (`backend/docs/api-contract.md`).
- Компиляция тестов идёт против android.jar: в тестах доступно только API `org.json`,
  которое есть в Android (например, `keys()`, но не `keySet()`).
- Проверка на телефоне по локальной сети без HTTPS невозможна — только через ngrok.

## Отвергнутые варианты

- Retrofit + OkHttp + kotlinx.serialization — три зависимости и плагин сериализации ради
  четырёх запросов.
- Ktor client — то же плюс собственная модель движков.
- Разрешить cleartext HTTP для локальной сети — лишняя поверхность атаки; ngrok и так даёт HTTPS.
