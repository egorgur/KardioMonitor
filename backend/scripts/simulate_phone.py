"""Имитация телефона пациента — для репетиции демо без браслета.

Регистрирует пациента, отправляет историю за вчера и сегодня (до текущей минуты) и «пульс сейчас»
раз в 2 секунды. Данные выдуманные: пульс покоя ~68, прогулка днём, ночью браслет снят.

    uv run python scripts/simulate_phone.py http://127.0.0.1:8000 --name "Демо Пациент"
    uv run python scripts/simulate_phone.py https://<адрес>.ngrok-free.app --live-seconds 120
"""

import argparse
import math
import random
import time
from datetime import UTC, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import httpx

BATCH_MINUTES = 1440


def _minute(started_at: datetime, rng: random.Random) -> dict[str, Any]:
    hour = started_at.hour + started_at.minute / 60
    if hour < 7:  # ночью браслет на зарядке
        return {"started_at": started_at.isoformat(), "steps": 0, "heart_rate": None,
                "kind": "charging", "kind_code": None, "intensity": 0}  # fmt: skip
    walking = 10 <= hour < 11 or 17 <= hour < 17.75
    steps = rng.randint(70, 110) if walking else max(0, int(rng.gauss(6, 8)))
    base = 68 + 4 * math.sin(hour / 24 * 2 * math.pi) + (30 if walking else 0)
    heart_rate = round(base + rng.gauss(0, 3)) if rng.random() < 0.9 else None
    return {
        "started_at": started_at.isoformat(),
        "steps": steps,
        "heart_rate": heart_rate,
        "kind": "other",
        "kind_code": 80 if walking else 1,
        "intensity": min(255, steps),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("server", help="адрес сервера, например http://127.0.0.1:8000")
    parser.add_argument("--name", default="Демо Пациент")
    parser.add_argument("--time-zone", default="Asia/Novosibirsk")
    parser.add_argument("--live-seconds", type=int, default=60, help="сколько слать «пульс сейчас»")
    parser.add_argument("--history-days", type=int, default=1, help="сколько прошлых дней истории")
    args = parser.parse_args()
    zone = ZoneInfo(args.time_zone)
    rng = random.Random(7)

    with httpx.Client(base_url=args.server, timeout=30) as client:
        registration = client.post(
            "/api/v1/register", json={"name": args.name, "time_zone": args.time_zone}
        )
        registration.raise_for_status()
        client.headers["Authorization"] = f"Bearer {registration.json()['token']}"
        print(f"пациент {args.name}: {registration.json()['patient_id']}")

        now = datetime.now(zone).replace(second=0, microsecond=0)
        start = now.replace(hour=0, minute=0) - timedelta(days=args.history_days)
        minutes = [
            _minute(start + timedelta(minutes=i), rng)
            for i in range(int((now - start).total_seconds() // 60))
        ]
        for offset in range(0, len(minutes), BATCH_MINUTES):
            receipt = client.post(
                "/api/v1/me/activity-minutes",
                json={"minutes": minutes[offset : offset + BATCH_MINUTES]},
            )
            receipt.raise_for_status()
            print(f"история: {receipt.json()}")

        for _ in range(args.live_seconds // 2):
            reading = {
                "measured_at": datetime.now(UTC).isoformat(),
                "heart_rate": round(rng.gauss(74, 3)),
            }
            client.post("/api/v1/me/live-heart-rate", json=reading).raise_for_status()
            time.sleep(2)
        print("готово")


if __name__ == "__main__":
    main()
