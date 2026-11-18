# ADR-0010: Секреты утилиты персонала — шифрование ключом Android Keystore

- Статус: принято (2026-10-29)
- Дата: 2026-10-29

## Контекст

Утилита хранит ключи всех браслетов клиники: утечка = доступ к пульсу всех пациентов. В
приложении пациента ключ лежит в обычных SharedPreferences (план — Keystore). Библиотека
`androidx.security:security-crypto` (`EncryptedSharedPreferences`) объявлена устаревшей
(deprecated) в версии 1.1.0, новых выпусков не будет.

## Решение

- Секрет (ключ браслета) шифруется AES-256-GCM ключом из Android Keystore
  (`KeyGenParameterSpec`, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, `setUnlockedDeviceRequired(true)`,
  ключ не экспортируется). Шифротекст и IV хранятся в SQLite утилиты (`SQLiteOpenHelper`,
  как `KardioDatabase` в `:app`) рядом с открытыми полями (MAC, метка).
- Обёртка — `platform.keystore.SecretBox` (`seal(ByteArray): Sealed`, `open(Sealed): ByteArray`);
  без библиотек — `javax.crypto` и `java.security.KeyStore` есть в Android.
- `minSdk` утилиты — 28 (`setUnlockedDeviceRequired` — API 28). Телефоны сотрудников выдаёт
  клиника, старые версии Android не нужны.
- Пароль аккаунта Xiaomi и сеанс облака не хранятся вовсе (ADR-0008).
- `android:allowBackup="false"`, правила `dataExtractionRules` исключают всё: шифротекст без
  ключа Keystore бесполезен, а резервная копия — лишний канал утечки.

## Последствия

- Скопированная база утилиты без телефона не раскрывает ключи.
- Сброс блокировки экрана или очистка данных уничтожает ключ Keystore — ключи браслетов
  нужно получить из облака заново (они там остаются). Это допустимо: облако — источник истины.
- Шифрование проверяется инструментальным тестом на устройстве (`androidTest`): в JVM-тестах
  Keystore нет; в JVM слайсы тестируются с фейком порта `SecretBox`.
- Тот же подход — кандидат для приложения пациента (отдельной задачей и ADR).

## Отвергнутые варианты

- `EncryptedSharedPreferences` — deprecated.
- Хранить ключи только в облаке и получать при каждой выдаче — выдача ломается без интернета.
- SQLCipher — новая нативная зависимость ради одной таблицы.
