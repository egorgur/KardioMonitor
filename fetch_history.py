#!/usr/bin/env python3
"""Выгрузка поминутной истории (пульс, шаги, активность, сон) с Mi Smart Band 6.

Браслет сам пишет запись за каждую минуту. Порядок обмена (как в Gadgetbridge,
FetchActivityOperation):
  1. 0x0004 <- 01 01 <время начала>   браслет отвечает 10 01 01 <кол-во> <фактическое начало>
  2. 0x0004 <- 02                      данные идут уведомлениями в 0x0005: [счётчик][записи...]
  3. браслет присылает 10 02 01 — порция закончилась.
Порция ограничена по размеру, поэтому запрос повторяется с конца предыдущей порции.

Подтверждение 03 («данные получены») не отправляется: после него браслет считает
данные выгруженными, и они могут не попасть в Mi Fitness.

Запись за минуту (8 байт): kind, intensity, steps, heart_rate, ?, sleep, deep_sleep, rem_sleep.
"""

import argparse
import asyncio
import csv
import datetime as dt
import os
import sys
from collections import defaultdict
from dataclasses import dataclass

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError

from miband6_poc import AuthError, authenticate, huami_uuid, parse_key

UUID_FETCH_CONTROL = huami_uuid(0x0004)
UUID_FETCH_DATA = huami_uuid(0x0005)

DATA_TYPE_ACTIVITY = 0x01
CMD_START_DATE = 0x01
CMD_FETCH_DATA = 0x02
SAMPLE_SIZE = 8  # Mi Band 6
MAX_ROUNDS = 30
PACKET_TIMEOUT = 15

# Типы активности (HuamiExtendedSampleProvider)
KIND_NOT_WORN = 115
KIND_CHARGING = 118
KIND_SLEEP = 120


@dataclass
class Sample:
    timestamp: dt.datetime
    kind: int
    intensity: int
    steps: int
    heart_rate: int | None
    sleep: int
    deep_sleep: int
    rem_sleep: int


def valid_heart_rate(value: int) -> bool:
    # 0 — не измерялся, 255 — ошибка; границы как в Gadgetbridge (HeartRateUtils)
    return 10 < value < 250


def encode_since(since: dt.datetime) -> bytes:
    """Время в формате браслета: год(2) месяц день час минута, 0, смещение в четвертях часа."""
    offset = since.utcoffset() or dt.timedelta()
    quarters = int(offset.total_seconds() // 900)
    return (since.year.to_bytes(2, "little")
            + bytes([since.month, since.day, since.hour, since.minute, 0, quarters & 0xFF]))


def decode_timestamp(data: bytes) -> dt.datetime:
    """год(2) месяц день час минута секунда [смещение в четвертях часа, со знаком]."""
    year = int.from_bytes(data[0:2], "little")
    tz = dt.timezone.utc
    if len(data) > 7:
        tz = dt.timezone(dt.timedelta(minutes=15 * int.from_bytes(data[7:8], "little", signed=True)))
    return dt.datetime(year, data[2], data[3], data[4], data[5], data[6], tzinfo=tz)


def parse_samples(start: dt.datetime, raw: bytes) -> list[Sample]:
    samples = []
    for i in range(0, len(raw) - len(raw) % SAMPLE_SIZE, SAMPLE_SIZE):
        kind, intensity, steps, hr, _, sleep, deep, rem = raw[i:i + SAMPLE_SIZE]
        samples.append(Sample(
            timestamp=start + dt.timedelta(minutes=i // SAMPLE_SIZE),
            kind=kind, intensity=intensity, steps=steps,
            heart_rate=hr if valid_heart_rate(hr) else None,
            sleep=sleep, deep_sleep=deep, rem_sleep=rem,
        ))
    return samples


class ActivityFetcher:
    def __init__(self, client: BleakClient):
        self.client = client
        self.control: asyncio.Queue[bytes] = asyncio.Queue()
        self.buffer = bytearray()
        self.last_counter = -1
        self.broken = False

    async def start(self) -> None:
        await self.client.start_notify(UUID_FETCH_CONTROL,
                                       lambda _, d: self.control.put_nowait(bytes(d)))
        await self.client.start_notify(UUID_FETCH_DATA, self._on_data)

    def _on_data(self, _, data: bytearray) -> None:
        if data[0] != (self.last_counter + 1) & 0xFF:
            print(f"  нарушен порядок пакетов: {data[0]} после {self.last_counter}")
            self.broken = True
            return
        self.last_counter = data[0]
        self.buffer += data[1:]

    async def _expect(self, command: int) -> bytes:
        while True:
            resp = await asyncio.wait_for(self.control.get(), timeout=PACKET_TIMEOUT)
            if resp[:2] == bytes([0x10, command]):
                if resp[2] != 0x01:
                    raise RuntimeError(f"браслет отклонил команду {command:#04x}: {resp.hex()}")
                return resp
            print(f"  (пропущен ответ {resp.hex()})")

    async def fetch_round(self, since: dt.datetime) -> tuple[dt.datetime, bytes] | None:
        """Одна порция. None — с этого момента данных нет."""
        self.buffer.clear()
        self.last_counter = -1
        self.broken = False

        command = bytes([CMD_START_DATE, DATA_TYPE_ACTIVITY]) + encode_since(since)
        await self.client.write_gatt_char(UUID_FETCH_CONTROL, command)
        resp = await self._expect(CMD_START_DATE)
        expected = int.from_bytes(resp[3:7], "little")
        start = decode_timestamp(resp[7:])
        if expected == 0:
            return None

        await self.client.write_gatt_char(UUID_FETCH_CONTROL, bytes([CMD_FETCH_DATA]))
        await self._expect(CMD_FETCH_DATA)
        if self.broken:
            raise RuntimeError("порция пришла с пропусками пакетов")
        return start, bytes(self.buffer)


async def fetch_history(client: BleakClient, since: dt.datetime) -> list[Sample]:
    fetcher = ActivityFetcher(client)
    await fetcher.start()
    samples: list[Sample] = []
    for _ in range(MAX_ROUNDS):
        result = await fetcher.fetch_round(since)
        if result is None:
            break
        start, raw = result
        chunk = parse_samples(start, raw)
        print(f"  порция с {start:%Y-%m-%d %H:%M}: {len(chunk)} мин")
        if not chunk:
            break
        samples += chunk
        since = chunk[-1].timestamp + dt.timedelta(minutes=1)
        if since >= dt.datetime.now(since.tzinfo):
            break
    return samples


def save_csv(path: str, samples: list[Sample]) -> None:
    with open(path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["timestamp", "heart_rate", "steps", "kind", "intensity",
                         "sleep", "deep_sleep", "rem_sleep"])
        for s in samples:
            writer.writerow([s.timestamp.isoformat(), s.heart_rate or "", s.steps, s.kind,
                             s.intensity, s.sleep, s.deep_sleep, s.rem_sleep])


def print_summary(samples: list[Sample]) -> None:
    if not samples:
        print("Данных нет.")
        return
    by_hour: dict[dt.datetime, list[Sample]] = defaultdict(list)
    for s in samples:
        by_hour[s.timestamp.replace(minute=0, second=0)].append(s)

    print(f"\n{'час':<17} {'шаги':>6} {'пульс мин/ср/макс':>18} {'замеров':>8} {'не надет':>9}")
    for hour, items in sorted(by_hour.items()):
        hrs = [s.heart_rate for s in items if s.heart_rate]
        hr = f"{min(hrs)}/{sum(hrs) / len(hrs):.0f}/{max(hrs)}" if hrs else "—"
        not_worn = sum(s.kind == KIND_NOT_WORN for s in items)
        print(f"{hour:%Y-%m-%d %H:00} {sum(s.steps for s in items):>6} {hr:>18} "
              f"{len(hrs):>8} {not_worn:>8}м")

    hrs = [s.heart_rate for s in samples if s.heart_rate]
    print(f"\nВсего: {len(samples)} мин, шагов {sum(s.steps for s in samples)}, "
          f"минут с пульсом {len(hrs)} ({100 * len(hrs) / len(samples):.0f}%)")
    if hrs:
        print(f"Пульс: мин {min(hrs)}, средний {sum(hrs) / len(hrs):.0f}, макс {max(hrs)}")


def parse_since(value: str) -> dt.datetime:
    now = dt.datetime.now().astimezone()
    if value.endswith("h") and value[:-1].isdigit():
        return now - dt.timedelta(hours=int(value[:-1]))
    if value.endswith("d") and value[:-1].isdigit():
        return now - dt.timedelta(days=int(value[:-1]))
    parsed = dt.datetime.fromisoformat(value)
    return parsed if parsed.tzinfo else parsed.astimezone()


async def run(address: str, key: bytes, since: dt.datetime, csv_path: str,
              legacy_auth: bool) -> int:
    print(f"Поиск {address}...")
    device = await BleakScanner.find_device_by_address(address, timeout=20)
    if device is None:
        print("Браслет не найден. Выключите Bluetooth на телефоне.")
        return 1
    async with BleakClient(device, timeout=30) as client:
        print(f"Подключено к {device.name}")
        await authenticate(client, key, legacy_auth)
        print(f"Выгрузка истории с {since:%Y-%m-%d %H:%M}...")
        samples = await fetch_history(client, since)
    save_csv(csv_path, samples)
    print(f"Сохранено {len(samples)} записей в {csv_path}")
    print_summary(samples)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mac", required=True, help="MAC-адрес браслета")
    parser.add_argument("--key", default=os.environ.get("MIBAND_AUTH_KEY"),
                        help="auth key (32 hex), по умолчанию из MIBAND_AUTH_KEY")
    parser.add_argument("--since", default="24h",
                        help="начало периода: 24h, 3d или 2026-10-08T00:00 (по умолчанию 24h)")
    parser.add_argument("--csv", default="history.csv", help="файл для записи (history.csv)")
    parser.add_argument("--legacy-auth", action="store_true",
                        help="старая авторизация (прошивки до 1.0.6.10)")
    args = parser.parse_args()
    if not args.key:
        parser.error("укажите --key или MIBAND_AUTH_KEY")

    try:
        return asyncio.run(run(args.mac, parse_key(args.key), parse_since(args.since),
                               args.csv, args.legacy_auth))
    except KeyboardInterrupt:
        print("\nОстановлено")
        return 0
    except AuthError as e:
        print(f"Ошибка авторизации: {e}")
        return 2
    except (BleakError, asyncio.TimeoutError, RuntimeError) as e:
        print(f"Ошибка: {e!r}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
