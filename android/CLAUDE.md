# KardioMonitor — Android

Приложение для наблюдения за пациентами в кардиореабилитации после инфаркта: читает
пульс и шаги с Xiaomi Mi Smart Band 6 по BLE, хранит поминутную историю, показывает
статистику. Kotlin, Jetpack Compose, minSdk 26, один Gradle-модуль `:app`, без DI-фреймворка.

## Команды

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug   # полная проверка — перед завершением задачи
./gradlew testDebugUnitTest --tests '<класс>'         # один тест-класс
./gradlew installDebug                                # на телефон по adb
adb logcat -v time -s KardioBLE:*                     # журнал обмена с браслетом
```

JDK 21, Gradle 9.4 (wrapper), AGP 9.1 — встроенный Kotlin, плагин `kotlin-android` не подключать.
Git общий на `KardioMonitor/` (корень — Python-прототип), настройки агента — свои в
`android/.claude/`: Claude Code запускать из `android/`.

## Архитектура — читать перед изменением структуры

Vertical Slice Architecture + DDD: код по контекстам (`features.band`, `features.monitoring`)
и слайсам-сценариям, правила предметной области — в доменных типах, протокол браслета
изолирован в `platform.huami`. Подробно: [docs/architecture.md](docs/architecture.md).

- **IMPORTANT: слайсы не импортируют другие слайсы.** Связь — через доменные типы,
  `features.<контекст>.api` и Flow репозиториев.
- Границы и проверяемые правила кода ловят архитектурные тесты (`src/test/.../architecture/`,
  [ADR-0005](docs/adr/0005-architecture-tests-without-library.md)). Упал — исправлять код;
  ослаблять тест или добавлять исключение — только с согласия человека.
- Домен (`features.*.domain`) — чистый Kotlin: без `android.*`, Compose и `platform.*`.
- Имена — из глоссария [docs/domain-model.md](docs/domain-model.md). Нет термина —
  добавить в глоссарий. Без суффиксов `Manager`/`Helper`/`Util`.
- Миграция прототипа со слоёв на эту структуру завершена (architecture.md). Слоёв
  `ble/`, `data/`, `ui/` больше нет — не создавать их заново.

## Правила кода

Полностью: [docs/coding-rules.md](docs/coding-rules.md). Главное:

- Value-типы вместо примитивов (`HeartRate`, `BandAddress`), `sealed` вместо int-кодов.
- Файл ≤ 300 строк, функция ≤ 40; один публичный тип на файл; точка входа слайса — единственное `public`.
- `CancellationException` всегда пробрасывать; без `runCatching`; не глотать исключения молча.
- Без бизнес-логики в composable; состояние экрана — неизменяемый `UiState`.
- Комментарии и тексты UI — на русском, идентификаторы — на английском.
- Новая зависимость — только с ADR ([docs/adr/](docs/adr/)) и согласием человека.

## Тесты

[docs/testing.md](docs/testing.md). Новое правило предметной области или баг — сначала
тест. Имена тестов — поведение на русском в обратных кавычках:
`` fun `пульс 255 означает, что замера не было`() ``. Фейки портов вместо моков.

## Браслет — неочевидные вещи

Протокол: [docs/ble-protocol.md](docs/ble-protocol.md).

- **IMPORTANT: изменения протокола (шифрование, пакеты, форматы) — только с тестами на
  эталонных байтах** из Python-реализации в корне репозитория (`../huami2021.py`,
  `../fetch_history.py`), как в `Huami2021Test`.
- Прошивка 1.0.6.10 требует авторизацию Huami 2021 (ECDH B-163); старая отклоняется.
- Не отправлять браслету `03` после выгрузки истории — он удалит данные ([ADR-0002](docs/adr/0002-keep-history-on-band.md)).
- Браслет держит одно соединение: все сценарии — через общий сеанс связи
  ([ADR-0003](docs/adr/0003-single-band-connection.md)): `features.band.api.BandConnection`.
  Не создавать `GattClient` в слайсах; браслет — через порты `BandLink` / `ActivitySource`.
- Никогда не логировать и не коммитить ключ авторизации и содержимое QR.
- Проверка на устройстве — по журналу `KardioBLE` (что искать — в testing.md).
  Телефон пациента: не делать `pm clear`, не удалять базу.

## Инструменты агента

- Skills проекта: `/new-slice <контекст>/<слайс>`, `/protocol-change` (`/migration-step` — для
  истории: миграция завершена).
- Подагент `architecture-reviewer` — ревью диффа перед отчётом.
- Stop-hook запускает `testDebugUnitTest`, если менялся код; PreToolUse-hook блокирует
  опасные adb-команды и вывод ключа. Внешние skills (`jetpack-compose`, `security`,
  `testing-setup`) и их установка — в [ai-agent-workflow.md](docs/ai-agent-workflow.md).

## Порядок работы

[docs/ai-agent-workflow.md](docs/ai-agent-workflow.md): разобраться → план (если файлов
несколько) → реализация в слайсе → проверка командами → обновить docs (глоссарий,
протокол, ADR) → ревью (`architecture-reviewer`). Завершая задачу, показывать вывод
проверок, а не утверждать успех на словах.
