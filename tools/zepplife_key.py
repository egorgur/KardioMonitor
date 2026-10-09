#!/usr/bin/env python3
"""Получение auth key браслета, сопряжённого в Zepp Life через аккаунт Xiaomi.

Вход выполняется в браузере (с 2FA, если она включена), пароль скрипту не передаётся:
  1. открыть выведенную ссылку и войти в аккаунт Xiaomi;
  2. после входа браузер перейдёт на hm.xiaomi.com/watch.do?code=... —
     страница может не открыться, это нормально: скопировать адрес из адресной строки;
  3. вставить адрес сюда. Код одноразовый и живёт недолго — вставлять сразу.

Схема взята из huami-token до переписывания (коммит a29f739^).
"""

import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

XIAOMI_OAUTH_URL = (
    "https://account.xiaomi.com/oauth2/authorize?skip_confirm=false"
    "&client_id=2882303761517383915&pt=0&scope=1+6000+16001+20000"
    "&redirect_uri=https%3A%2F%2Fhm.xiaomi.com%2Fwatch.do"
    "&_locale=en_US&response_type=code"
)

LOGIN_URLS = [
    "https://account.huami.com/v2/client/login",
    "https://api-mifit-us2.zepp.com/v2/client/login",
]
DEVICES_URLS = [
    "https://api-mifit.zepp.com/users/{user_id}/devices",
    "https://api-mifit-us2.huami.com/users/{user_id}/devices",
]

LOGIN_PAYLOAD = {
    "dn": "account.huami.com,api-user.huami.com,app-analytics.huami.com,api-watch.huami.com,"
          "api-analytics.huami.com,api-mifit.huami.com",
    "app_version": "5.9.2-play_100355",
    "source": "com.huami.watch.hmwatchmanager",
    "lang": "en",
    "device_model": "android_phone",
    "allow_registration": "false",
    "app_name": "com.huami.midong",
    "third_name": "mi-watch",
    "grant_type": "request_token",
    "country_code": "US",
}

USER_AGENT = "Zepp/9.12.5 (Pixel 4; Android 12; Density/2.75)"


def http(method: str, url: str, data: dict | None = None,
         headers: dict | None = None) -> tuple[int, str]:
    body = urllib.parse.urlencode(data).encode() if data else None
    req = urllib.request.Request(url, data=body, method=method,
                                 headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def extract_code(pasted: str) -> str:
    params = urllib.parse.parse_qs(urllib.parse.urlparse(pasted.strip()).query)
    if "code" not in params:
        sys.exit("В адресе нет параметра code. Скопируйте адрес целиком после перехода на watch.do.")
    return params["code"][0]


def login(code: str) -> dict:
    payload = {**LOGIN_PAYLOAD, "code": code, "device_id": str(uuid.uuid4())}
    for url in LOGIN_URLS:
        status, text = http("POST", url, payload)
        try:
            result = json.loads(text)
        except json.JSONDecodeError:
            result = {}
        if "token_info" in result:
            return result["token_info"]
        print(f"  {url}: HTTP {status}: {text[:300]}")
    sys.exit("Не удалось обменять код на токен Huami (код одноразовый — при повторе войдите заново).")


def get_devices(user_id: str, app_token: str) -> list[dict]:
    query = urllib.parse.urlencode({"enableMultiDevice": "true"})
    for url in DEVICES_URLS:
        status, text = http("GET", url.format(user_id=user_id) + "?" + query,
                            headers={"apptoken": app_token, "appplatform": "android_phone",
                                     "appname": "com.huami.midong"})
        if status == 200:
            return json.loads(text).get("items", [])
        print(f"  {url}: HTTP {status}: {text[:300]}")
    sys.exit("Не удалось получить список устройств.")


def main() -> None:
    print(__doc__)
    print(XIAOMI_OAUTH_URL, "\n")
    code = extract_code(input("Адрес после входа: "))

    token_info = login(code)
    user_id = str(token_info["user_id"])
    print(f"\nВход в Huami выполнен, user_id={user_id}")

    devices = get_devices(user_id, token_info["app_token"])
    if not devices:
        print("К аккаунту не привязано ни одного устройства. Браслет сопряжён в Zepp Life "
              "под этим же аккаунтом Xiaomi?")
    for device in devices:
        info = json.loads(device.get("additionalInfo") or "{}")
        active = "да" if device.get("activeStatus") == 1 else "нет"
        print(f"\nMAC: {device.get('macAddress')}  активен: {active}  "
              f"прошивка: {device.get('firmwareVersion')}")
        print(f"Key: 0x{info.get('auth_key') or '(нет)'}")


if __name__ == "__main__":
    main()
