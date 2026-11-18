# Облако Xiaomi: получение ключей браслетов

Знание о протоколе облака, которое нельзя вывести из кода. Источник —
[huami-token](https://codeberg.org/argrento/huami-token) (MIT, © Kirill Snezhko; локальная копия —
`../../huami-token/`, модули `xiaomi.py`, `mi_crypto.py`, `zepp.py`) и `tools/zepplife_key.py`.
API неофициальный и может измениться без предупреждения (ADR-0008).

> Статус: **не проверено из утилиты.** Описано по `huami-token`, проверенному на ноутбуке.
> Шаг 3 плана ([README.md](README.md#план-разработки)) подтверждает поток на аккаунте клиники и
> дополняет этот документ.

## Путь 1. Mi Fitness (Xiaomi Wear) — основной

### Вход (`sid=miothealth`)

1. `GET https://account.xiaomi.com/pass/serviceLogin?sid=miothealth&_json=true` с cookie
   `deviceId` → JSON (после префикса `&&&START&&&`): `_sign`, `qs`, `callback`.
2. `POST https://account.xiaomi.com/pass/serviceLoginAuth2`, форма: `user`, `hash` =
   `MD5(пароль)` в hex **верхним регистром**, `_sign`, `qs`, `callback`, `sid=miothealth`,
   `_json=true`, `_locale`. Ответ (тот же префикс): `code=0`, `ssecurity`, `nonce`, `userId`,
   `cUserId`, `location`.
   - `code≠0` — неверный логин или пароль → `SignInResult.WrongCredentials`.
   - Xiaomi требует подтверждение входа (капча, вход с нового устройства) — пароль верный, но
     этим путём не войти → `SignInResult.ConfirmationRequired`. Признак в ответе (по другим
     клиентам — поля `notificationUrl`/`captchaUrl`) `huami-token` не обрабатывает: **уточнить в
     шаге 3**. 2FA у аккаунта клиники нет (решение 2026-10-29).
3. `GET <location>&clientSign=<urlencode(base64(SHA1("nonce=<nonce>&<ssecurity>")))>` →
   cookie `serviceToken`.

Сеанс = `userId`, `cUserId`, `ssecurity`, `serviceToken`. Хранится только в памяти ACL.

### Запрос с шифрованием

Каждый запрос к `hlth.io.mi.com` шифруется и подписывается (`mi_crypto.py`):

| Шаг | Как |
|---|---|
| nonce | `base64(8 случайных байт ‖ int32_be((now_ms + timeDiff) / 60000))` |
| ключ RC4 | `base64(SHA256(base64dec(ssecurity) ‖ base64dec(nonce)))` |
| RC4 | Стандартный RC4 с ключом `base64dec(ключ RC4)`, **первые 1024 байта гаммы отбрасываются** |
| путь для подписи | Путь URL (при пустом `path_prefix` — как есть: `/app/v1/source/get_source_list`) |
| подпись и шифрование | `rc4_hash__` = `base64(SHA1("POST&<путь>&k1=v1&…&<ключ RC4>"))` по открытым параметрам (ключи по алфавиту) и добавляется к ним; затем все параметры, включая `rc4_hash__`, шифруются **одним потоком RC4** по алфавиту ключей → base64; `signature` — та же SHA1-схема по зашифрованным значениям; добавляется `_nonce`. `ssecurity` не передаётся |
| cookies | `cUserId`, `serviceToken`, `locale=en_us` |
| заголовки | `User-Agent` Android-клиента, `region_tag` — регион аккаунта (`ru`, …) |
| ответ | base64 → RC4 (новый поток с тем же ключом, сброс 1024) → JSON |

Точную последовательность (порядок шифрования параметров, имена служебных полей) переносить
из `mi_encrypt_params`/`mi_decrypt_response` **дословно**, с тестом на эталонных значениях
([testing.md](testing.md)). Пересказ в этой таблице — для понимания, не для реализации.

### Список браслетов

`POST https://hlth.io.mi.com/app/v1/source/get_source_list`, параметр `data` =
`{"page_size":50,"status":1}` (1 — привязанные носимые устройства). Ответ:
`result.list[]`: `name`, `detail` с `mac` и `auth_key` (hex). Данные — на базовом домене
`hlth.io.mi.com`, не на региональном.

Перевод в домен (ACL): `mac` → `BandAddress` (верхний регистр), `auth_key` → `AuthKey` (нижний
регистр, без `0x`), `name` → `BandModel`. Запись без ключа или с неверным форматом —
пропускается с предупреждением в журнале (без значений).

## Путь 2. Zepp Life — запасной

Для браслетов, привязанных в Zepp Life (бывший Mi Fit) под аккаунтом Xiaomi:

1. WebView открывает
   `https://account.xiaomi.com/oauth2/authorize?skip_confirm=false&client_id=2882303761517383915&pt=0&scope=1+6000+16001+20000&redirect_uri=https%3A%2F%2Fhm.xiaomi.com%2Fwatch.do&_locale=en_US&response_type=code`.
   Пароль и 2FA вводятся на странице Xiaomi — **утилита их не видит**.
2. `WebViewClient.shouldOverrideUrlLoading` перехватывает переход на `hm.xiaomi.com/watch.do?code=…`,
   страницу не загружает, берёт `code` (одноразовый, живёт недолго).
3. `POST https://account.huami.com/v2/client/login` (запасной — `api-mifit-us2.zepp.com`), форма —
   `LOGIN_PAYLOAD` из `zepplife_key.py` + `code`, `device_id` (UUID) → `token_info.app_token`, `user_id`.
4. `GET https://api-mifit.zepp.com/users/<user_id>/devices?enableMultiDevice=true`, заголовки
   `apptoken`, `appplatform=android_phone`, `appname=com.huami.midong` → `items[]`:
   `macAddress`, `additionalInfo` (JSON-строка) → `auth_key`.

WebView: только этот домен и редирект, JavaScript включён (нужен странице входа), без
`addJavascriptInterface`, cookies WebView очищаются после входа.

## Исследование перед кодом (шаг 3 плана)

На аккаунте клиники (2FA нет), скриптом `huami-token`, без кода в основной ветке:

1. Вход паролем и `get_source_list` проходят; в ответе есть все браслеты аккаунта с `auth_key`.
2. Регион аккаунта (`region_tag`) и нужен ли он для `get_source_list`.
3. Чем отвечает `serviceLoginAuth2`, когда Xiaomi просит подтверждение (вход с нового
   устройства) — признак для `ConfirmationRequired`.

Итог записать сюда и в ADR-0008 (открытый вопрос о регионе).

### Если включат 2FA

Вход паролем перестанет работать. Тогда исследовать вход в WebView:
`https://account.xiaomi.com/pass/serviceLogin?sid=miothealth` (без `_json`) — даёт ли финальный
редирект (`location` с `nonce`, `ssecurity`) или cookie `passToken`, по которым повторный
`serviceLogin` выдаёт `ssecurity` и `serviceToken` без пароля. До итогов — Zepp Life или ручной ввод.

## Ошибки и их перевод

| Ситуация | Признак | Домен |
|---|---|---|
| Нет сети, таймаут, `5xx` | `IOException` | `Unavailable` |
| Неверный пароль | `code≠0` в `serviceLoginAuth2` | `WrongCredentials` |
| Нужно подтверждение (капча, новое устройство) | Признак уточняется в шаге 3 (вероятно, `notificationUrl`/`captchaUrl`) | `ConfirmationRequired` |
| Сеанс истёк | `401` или код «not login» в ответе `hlth` | `NotSignedIn` — войти заново |
| Формат ответа изменился | нет ожидаемых полей, не расшифровывается | `Unavailable` + журнал «формат облака изменился» (без тела ответа) |
