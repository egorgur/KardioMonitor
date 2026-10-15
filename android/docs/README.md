# Документация Android-приложения

| Документ | Когда читать |
|---|---|
| [architecture.md](architecture.md) | Перед любой задачей, которая добавляет или переносит код: VSA + DDD, структура пакетов, правила зависимостей, план миграции |
| [domain-model.md](domain-model.md) | Когда нужно назвать класс или понять термин: глоссарий, агрегаты, инварианты |
| [coding-rules.md](coding-rules.md) | При написании кода: имена, размеры, типы, ошибки, корутины, Compose, логирование |
| [testing.md](testing.md) | При написании тестов и проверке на телефоне |
| [ble-protocol.md](ble-protocol.md) | При работе с браслетом: UUID, команды, авторизация, формат истории |
| [ai-agent-workflow.md](ai-agent-workflow.md) | Как ставить задачи агенту и как агенту их выполнять; рекомендуемые skills |
| [adr/](adr/) | Почему сделано именно так. Новое значимое решение — новый ADR по [шаблону](adr/0000-template.md) |

## Принятые решения

- [ADR-0001](adr/0001-vertical-slices-and-ddd.md) — Vertical Slice Architecture + DDD
- [ADR-0002](adr/0002-keep-history-on-band.md) — не подтверждать выгрузку истории браслету
- [ADR-0003](adr/0003-single-band-connection.md) — одно соединение с браслетом на всё приложение
- [ADR-0004](adr/0004-manual-di.md) — ручное внедрение зависимостей
- [ADR-0005](adr/0005-architecture-tests-without-library.md) — архитектурные тесты на JUnit без библиотеки
- [ADR-0006](adr/0006-http-without-library.md) — обмен с сервером: HttpURLConnection и org.json без библиотек

## Источники правил

- [Claude Code: best practices](https://code.claude.com/docs/en/best-practices), [CLAUDE.md и rules](https://code.claude.com/docs/en/memory) — Anthropic
- [android/skills](https://github.com/android/skills) — официальные skills Google для Android
  ([Android CLI](https://developer.android.com/tools/agents/android-cli))
- [mryll/skills: vertical-slice-architecture, test-namer](https://github.com/mryll/skills)
- [booklib-ai/booklib: domain-driven-design, effective-kotlin](https://github.com/booklib-ai/booklib)
- [Kotlin и DDD (JetBrains)](https://blog.jetbrains.com/kotlin/2025/04/domain-driven-design-guide/)
- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — протокол Mi Band
