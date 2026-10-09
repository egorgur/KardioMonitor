#!/usr/bin/env python3
"""Proof of concept: чтение пульса и шагов с Xiaomi Mi Smart Band 6 (XMSH15HM) по BLE.

Шаги проверки:
  1. найти браслет (scan),
  2. подключиться и прочитать Device Information (без авторизации),
  3. пройти авторизацию Huami ключом (auth key),
  4. прочитать батарею и шаги, подписаться на обновления шагов,
  5. включить непрерывное измерение пульса и печатать значения в реальном времени.

Все измерения можно писать в CSV (--csv) для дальнейшего анализа.
"""

import argparse
import asyncio
import csv
import datetime as dt
import os
import struct
import sys

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from huami2021 import ENDPOINT_AUTH, Auth2021, ChunkedDecoder, ChunkedEncoder


def huami_uuid(short: int) -> str:
    return f"0000{short:04x}-0000-3512-2118-0009af100700"


def sig_uuid(short: int) -> str:
    return f"0000{short:04x}-0000-1000-8000-00805f9b34fb"


# Проприетарные характеристики Huami (сервисы 0xFEE0 / 0xFEE1)
UUID_SENSOR = huami_uuid(0x0001)
UUID_BATTERY = huami_uuid(0x0006)
UUID_STEPS = huami_uuid(0x0007)
UUID_AUTH = huami_uuid(0x0009)
UUID_CHUNKED_WRITE = huami_uuid(0x0016)  # протокол Huami 2021
UUID_CHUNKED_READ = huami_uuid(0x0017)

# Стандартные характеристики Bluetooth SIG
UUID_HR_MEASUREMENT = sig_uuid(0x2A37)
UUID_HR_CONTROL = sig_uuid(0x2A39)
DEVICE_INFO = {
    "serial": sig_uuid(0x2A25),
    "hardware": sig_uuid(0x2A27),
    "firmware": sig_uuid(0x2A28),  # Huami отдаёт версию прошивки в Software Revision
}

# Команды
AUTH_REQUEST_RANDOM = b"\x82\x00\x02\x01\x00"  # для Mi Band 4/5/6 (crypt flags 0x80)
AUTH_SEND_KEY_PREFIX = b"\x83\x00"
HR_STOP_MANUAL = b"\x15\x02\x00"
HR_STOP_CONTINUOUS = b"\x15\x01\x00"
HR_START_CONTINUOUS = b"\x15\x01\x01"
HR_PING = b"\x16"  # без пинга браслет прекращает измерение примерно через 15 с
HR_PING_INTERVAL = 12
SENSOR_ENABLE = b"\x01\x03\x19"
SENSOR_START = b"\x02"

AUTH_ERRORS = {
    b"\x10\x82\x04": "браслет отклонил запрос (неверные auth/crypt flags для этой прошивки)",
    b"\x10\x83\x04": "неверный ключ авторизации",
    b"\x10\x83\x07": "прошивка требует протокол Huami 2021 (запустите без --legacy-auth)",
    b"\x10\x83\x08": "неверный ключ авторизации",
    b"\x10\x05\x25": "неверный ключ авторизации",
}


class AuthError(Exception):
    pass


def now() -> str:
    return dt.datetime.now().isoformat(timespec="seconds")


def parse_key(raw: str) -> bytes:
    raw = raw.strip().lower().removeprefix("0x")
    if len(raw) != 32:
        raise ValueError("ключ должен состоять из 32 hex-символов (16 байт)")
    return bytes.fromhex(raw)


def parse_heart_rate(data: bytes) -> int:
    # Heart Rate Measurement (0x2A37): бит 0 флагов — формат значения uint8/uint16
    if data[0] & 0x01:
        return struct.unpack_from("<H", data, 1)[0]
    return data[1]


def parse_steps(data: bytes) -> dict:
    # [0x0C, steps u32, distance_m u32, calories u32]
    result = {"steps": int.from_bytes(data[1:5], "little")}
    if len(data) >= 9:
        result["distance_m"] = int.from_bytes(data[5:9], "little")
    if len(data) >= 13:
        result["calories"] = int.from_bytes(data[9:13], "little")
    return result


class Recorder:
    """Печатает измерения и при необходимости пишет их в CSV."""

    def __init__(self, path: str | None):
        self._file = open(path, "a", newline="") if path else None
        self._writer = csv.writer(self._file) if self._file else None
        if self._file and self._file.tell() == 0:
            self._writer.writerow(["timestamp", "metric", "value"])

    def record(self, metric: str, value) -> None:
        ts = now()
        print(f"[{ts}] {metric}: {value}")
        if self._writer:
            self._writer.writerow([ts, metric, value])
            self._file.flush()

    def close(self) -> None:
        if self._file:
            self._file.close()


async def scan(timeout: float, show_all: bool) -> None:
    print(f"Сканирование {timeout:.0f} с...")
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    rows = []
    for device, adv in found.values():
        name = device.name or adv.local_name or ""
        if show_all or "band" in name.lower():
            rows.append((adv.rssi, device.address, name))
    if not rows:
        print("Браслет не найден. Если он подключён к телефону — выключите Bluetooth на телефоне "
              "(браслет держит только одно соединение и в это время не виден).")
        return
    for rssi, address, name in sorted(rows, reverse=True):
        print(f"{address}  RSSI {rssi:>4}  {name}")


async def dump_services(client: BleakClient) -> None:
    for service in client.services:
        print(f"[service] {service.uuid}  {service.description}")
        for char in service.characteristics:
            print(f"    {char.uuid}  {','.join(char.properties)}  {char.description}")


async def read_device_info(client: BleakClient, rec: Recorder) -> None:
    for label, uuid in DEVICE_INFO.items():
        try:
            value = await client.read_gatt_char(uuid)
            rec.record(label, value.decode(errors="replace").strip("\x00"))
        except BleakError:
            pass


class Chunked2021Channel:
    """Канал 0x0016/0x0017: шлёт сообщения кусками, собирает ответы и подтверждает их (ack).

    Остаётся подписанным весь сеанс: после авторизации браслет присылает свои сообщения
    и ждёт на них ack.
    """

    def __init__(self, client: BleakClient):
        self._client = client
        self._encoder = ChunkedEncoder()
        self._decoder = ChunkedDecoder()
        self._tasks: set[asyncio.Task] = set()
        self.messages: asyncio.Queue[tuple[int, bytes]] = asyncio.Queue()

    async def start(self) -> None:
        await self._client.start_notify(UUID_CHUNKED_READ, self._on_chunk)

    def _on_chunk(self, _, data: bytearray) -> None:
        result = self._decoder.feed(bytes(data))
        if result is None:
            return
        endpoint, payload, encrypted, ack = result
        if ack:
            task = asyncio.get_running_loop().create_task(
                self._client.write_gatt_char(UUID_CHUNKED_READ, ack, response=False))
            self._tasks.add(task)
            task.add_done_callback(self._tasks.discard)
        if not encrypted:
            self.messages.put_nowait((endpoint, payload))

    async def send(self, endpoint: int, data: bytes) -> None:
        for chunk in self._encoder.encode(endpoint, data):
            await self._client.write_gatt_char(UUID_CHUNKED_WRITE, chunk, response=False)


async def authenticate_2021(client: BleakClient, key: bytes) -> Chunked2021Channel:
    channel = Chunked2021Channel(client)
    await channel.start()
    auth = Auth2021(key)

    async def expect(command: int) -> bytes:
        while True:
            endpoint, payload = await asyncio.wait_for(channel.messages.get(), timeout=10)
            if endpoint != ENDPOINT_AUTH or payload[:2] != bytes([0x10, command]):
                print(f"  (пропущено сообщение 0x{endpoint:04x}: {payload.hex()})")
                continue
            if payload[2] != 0x01:
                reason = AUTH_ERRORS.get(payload[:3], "неожиданный ответ")
                raise AuthError(f"{reason}: {payload[:3].hex()}")
            return payload

    await channel.send(ENDPOINT_AUTH, auth.pubkey_command())
    challenge = await expect(0x04)
    await channel.send(ENDPOINT_AUTH, auth.answer_challenge(challenge))
    await expect(0x05)
    return channel


async def authenticate_legacy(client: BleakClient, key: bytes) -> None:
    responses: asyncio.Queue[bytes] = asyncio.Queue()
    await client.start_notify(UUID_AUTH, lambda _, data: responses.put_nowait(bytes(data)))

    async def expect(prefix: bytes) -> bytes:
        resp = await asyncio.wait_for(responses.get(), timeout=10)
        if not resp.startswith(prefix):
            reason = AUTH_ERRORS.get(resp[:3], "неожиданный ответ")
            raise AuthError(f"{reason}: {resp.hex()}")
        return resp

    try:
        await client.write_gatt_char(UUID_AUTH, AUTH_REQUEST_RANDOM)
        random_number = (await expect(b"\x10\x82\x01"))[3:19]

        encryptor = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
        encrypted = encryptor.update(random_number) + encryptor.finalize()
        await client.write_gatt_char(UUID_AUTH, AUTH_SEND_KEY_PREFIX + encrypted)
        await expect(b"\x10\x83\x01")
    finally:
        await client.stop_notify(UUID_AUTH)


async def authenticate(client: BleakClient, key: bytes, legacy_auth: bool = False) -> None:
    """Выбирает протокол: Huami 2021, если у браслета есть каналы 0x0016/0x0017."""
    use_2021 = (not legacy_auth
                and client.services.get_characteristic(UUID_CHUNKED_WRITE) is not None
                and client.services.get_characteristic(UUID_CHUNKED_READ) is not None)
    if use_2021:
        print("Авторизация (протокол Huami 2021)...")
        await authenticate_2021(client, key)
    else:
        print("Авторизация (старый протокол)...")
        await authenticate_legacy(client, key)
    print("Авторизация успешна")


async def try_write(client: BleakClient, uuid: str, data: bytes) -> None:
    try:
        await client.write_gatt_char(uuid, data)
    except BleakError as e:
        print(f"  (запись {data.hex()} в {uuid[4:8]} не удалась: {e})")


async def monitor(address: str, key: bytes | None, duration: float, csv_path: str | None,
                  dump: bool, legacy_auth: bool) -> int:
    print(f"Поиск {address}...")
    device = await BleakScanner.find_device_by_address(address, timeout=20)
    if device is None:
        print("Браслет не найден. Выключите Bluetooth на телефоне и проверьте MAC-адрес (--scan).")
        return 1

    rec = Recorder(csv_path)
    try:
        async with BleakClient(device, timeout=30) as client:
            print(f"Подключено к {device.name} ({device.address})")
            if dump:
                await dump_services(client)
            await read_device_info(client, rec)

            if key is None:
                print("Ключ не задан (--key / MIBAND_AUTH_KEY) — дальше без авторизации не пройти.")
                return 0

            await authenticate(client, key, legacy_auth)

            battery = await client.read_gatt_char(UUID_BATTERY)
            rec.record("battery_percent", battery[1])

            def on_steps(_, data: bytearray) -> None:
                for metric, value in parse_steps(bytes(data)).items():
                    rec.record(metric, value)

            on_steps(None, await client.read_gatt_char(UUID_STEPS))
            await client.start_notify(UUID_STEPS, on_steps)

            def on_heart_rate(_, data: bytearray) -> None:
                bpm = parse_heart_rate(bytes(data))
                if bpm == 0:
                    print(f"[{now()}] heart_rate: нет данных (идёт измерение или браслет снят)")
                else:
                    rec.record("heart_rate_bpm", bpm)

            await client.start_notify(UUID_HR_MEASUREMENT, on_heart_rate)
            await client.write_gatt_char(UUID_HR_CONTROL, HR_STOP_MANUAL)
            await client.write_gatt_char(UUID_HR_CONTROL, HR_STOP_CONTINUOUS)
            await try_write(client, UUID_SENSOR, SENSOR_ENABLE)
            await client.write_gatt_char(UUID_HR_CONTROL, HR_START_CONTINUOUS)
            await try_write(client, UUID_SENSOR, SENSOR_START)
            print(f"Измерение пульса запущено на {duration:.0f} с (Ctrl+C — остановить)")

            loop = asyncio.get_running_loop()
            deadline = loop.time() + duration
            try:
                while loop.time() < deadline:
                    await asyncio.sleep(min(HR_PING_INTERVAL, deadline - loop.time()))
                    await client.write_gatt_char(UUID_HR_CONTROL, HR_PING)
            finally:
                if client.is_connected:
                    await client.write_gatt_char(UUID_HR_CONTROL, HR_STOP_CONTINUOUS)
    except AuthError as e:
        print(f"Ошибка авторизации: {e}")
        return 2
    finally:
        rec.close()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scan", action="store_true", help="найти браслеты поблизости")
    parser.add_argument("--all", action="store_true", help="при --scan показать все BLE-устройства")
    parser.add_argument("--mac", help="MAC-адрес браслета")
    parser.add_argument("--key", default=os.environ.get("MIBAND_AUTH_KEY"),
                        help="auth key (32 hex), по умолчанию из MIBAND_AUTH_KEY")
    parser.add_argument("--duration", type=float, default=120, help="длительность записи, с")
    parser.add_argument("--csv", help="файл для записи измерений")
    parser.add_argument("--dump", action="store_true", help="вывести все сервисы и характеристики")
    parser.add_argument("--legacy-auth", action="store_true",
                        help="старая авторизация (прошивки до 1.0.6.10)")
    args = parser.parse_args()

    if args.scan:
        asyncio.run(scan(10, args.all))
        return 0
    if not args.mac:
        parser.error("укажите --mac (найти можно через --scan)")

    try:
        key = parse_key(args.key) if args.key else None
    except ValueError as e:
        parser.error(str(e))

    try:
        return asyncio.run(monitor(args.mac, key, args.duration, args.csv, args.dump,
                                   args.legacy_auth))
    except KeyboardInterrupt:
        print("\nОстановлено")
        return 0
    except (BleakError, asyncio.TimeoutError) as e:
        print(f"Ошибка BLE: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
