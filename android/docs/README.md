# Документация Android-приложения

| Документ | Когда читать |
|---|---|
| [architecture.md](architecture.md) | Перед любой задачей, которая добавляет или переносит код: VSA + DDD, структура пакетов, правила зависимостей, защитные механизмы |
| [domain-model.md](domain-model.md) | Когда нужно назвать класс или понять термин: глоссарий, агрегаты, инварианты |
| [coding-rules.md](coding-rules.md) | При написании кода: имена, размеры, типы, ошибки, корутины, Compose, логирование |
| [testing.md](testing.md) | При написании тестов и проверке на телефоне |
| [ble-protocol.md](ble-protocol.md) | При работе с браслетом: UUID, команды, авторизация, формат истории |
| [qr-contract.md](qr-contract.md) | При изменении QR-кода браслета: формат — контракт между утилитой персонала, `tools/make_qr.py` и приложением пациента |
| [staff-app/](staff-app/README.md) | Утилита персонала (второе приложение, спецификация): сценарии, архитектура, глоссарий, облако Xiaomi, безопасность |
| [ai-agent-workflow.md](ai-agent-workflow.md) | Как ставить задачи агенту и как агенту их выполнять; рекомендуемые skills |
| [adr/](adr/) | Почему сделано именно так. Новое значимое решение — новый ADR по [шаблону](adr/0000-template.md) |

## Принятые решения

- [ADR-0001](adr/0001-vertical-slices-and-ddd.md) — Vertical Slice Architecture + DDD
- [ADR-0002](adr/0002-keep-history-on-band.md) — не подтверждать выгрузку истории браслету
- [ADR-0003](adr/0003-single-band-connection.md) — одно соединение с браслетом на всё приложение
- [ADR-0004](adr/0004-manual-di.md) — ручное внедрение зависимостей
- [ADR-0005](adr/0005-architecture-tests-without-library.md) — архитектурные тесты на JUnit без библиотеки
- [ADR-0006](adr/0006-http-without-library.md) — обмен с сервером: HttpURLConnection и org.json без библиотек
- [ADR-0007](adr/0007-staff-app-multimodule.md) — утилита персонала в той же сборке, общий модуль протокола
- [ADR-0008](adr/0008-band-keys-from-xiaomi-cloud.md) — ключи браслетов из облака Xiaomi
- [ADR-0009](adr/0009-qr-generation-zxing-core.md) — QR-код в утилите: ZXing core
- [ADR-0010](adr/0010-staff-secrets-in-keystore.md) — секреты утилиты: шифрование ключом Android Keystore
- [ADR-0011](adr/0011-band-reissue-keeps-key.md) — повторная выдача браслета без смены ключа: синхронизация с момента привязки
- [ADR-0012](adr/0012-patient-secrets-in-keystore.md) — ключ браслета и токен пациента в Android Keystore
- [ADR-0013](adr/0013-clinic-live-heart-rate-request.md) — запрос пульса клиникой: CDM и `connectedDevice` foreground Worker

## Источники правил

- [Claude Code: best practices](https://code.claude.com/docs/en/best-practices), [CLAUDE.md и rules](https://code.claude.com/docs/en/memory) — Anthropic
- [android/skills](https://github.com/android/skills) — официальные skills Google для Android
  ([Android CLI](https://developer.android.com/tools/agents/android-cli))
- [mryll/skills: vertical-slice-architecture, test-namer](https://github.com/mryll/skills)
- [booklib-ai/booklib: domain-driven-design, effective-kotlin](https://github.com/booklib-ai/booklib)
- [Kotlin и DDD (JetBrains)](https://blog.jetbrains.com/kotlin/2025/04/domain-driven-design-guide/)
- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — протокол Mi Band
