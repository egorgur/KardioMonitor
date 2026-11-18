"""Слайс «Высокий пульс»: приём минут и «пульса сейчас» с телефона создаёт оповещения (ТР-08)."""

from datetime import datetime, timedelta
from typing import Any

from httpx import AsyncClient

from kardiomonitor.features.alerts.domain.patient_alert import AlertKind
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from tests.features.alerts.ports import Ports
from tests.features.rehab.plans import MOSCOW

TEN = datetime(2026, 10, 12, 10, 0, tzinfo=MOSCOW)
MINUTES_URL = "/api/v1/me/activity-minutes"


def _minute(offset: int, heart_rate: int | None) -> dict[str, Any]:
    return {
        "started_at": (TEN + timedelta(minutes=offset)).isoformat(),
        "steps": 0,
        "heart_rate": heart_rate,
        "kind": "other",
        "kind_code": 80,
        "intensity": 0,
    }


def _batch(*minutes_and_bpm: tuple[int, int | None]) -> dict[str, Any]:
    return {"minutes": [_minute(offset, bpm) for offset, bpm in minutes_and_bpm]}


async def test_пульс_выше_порога_в_принятых_минутах_одно_оповещение_до_140_за_6_минут(
    client: AsyncClient, ports: Ports
) -> None:
    batch = _batch((1, 135), (2, 140), (3, 138), (4, 100), (5, None), (6, 131))

    response = await client.post(MINUTES_URL, json=batch)

    alerts = ports.journal.of_kind(AlertKind.HIGH_HEART_RATE)
    assert response.status_code == 200
    assert len(alerts) == 1
    episode = alerts[0].episode
    assert episode is not None
    assert (episode.max_heart_rate, episode.duration_minutes) == (HeartRate(140), 6)
    assert ports.transaction.commits == 1


async def test_повторная_синхронизация_тех_же_минут_не_создаёт_дубликат(
    client: AsyncClient, ports: Ports
) -> None:
    batch = _batch((1, 135), (2, 140), (3, 138), (6, 131))

    await client.post(MINUTES_URL, json=batch)
    await client.post(MINUTES_URL, json=batch)

    assert len(ports.journal.of_kind(AlertKind.HIGH_HEART_RATE)) == 1


async def test_эпизод_через_два_пакета_одно_оповещение(client: AsyncClient, ports: Ports) -> None:
    await client.post(MINUTES_URL, json=_batch((1, 135), (2, 140)))
    await client.post(MINUTES_URL, json=_batch((4, 150), (5, 132)))

    alerts = ports.journal.of_kind(AlertKind.HIGH_HEART_RATE)
    assert len(alerts) == 1
    episode = alerts[0].episode
    assert episode is not None
    assert (episode.max_heart_rate, episode.duration_minutes) == (HeartRate(150), 5)


async def test_пульс_не_выше_порога_оповещений_не_создаёт(
    client: AsyncClient, ports: Ports
) -> None:
    await client.post(MINUTES_URL, json=_batch((1, 130), (2, 120)))

    assert ports.journal.alerts == {}


async def test_пульс_сейчас_выше_порога_создаёт_оповещение(
    client: AsyncClient, ports: Ports
) -> None:
    for seconds in (0, 3, 6):
        measured_at = (TEN + timedelta(seconds=seconds)).isoformat()
        await client.post(
            "/api/v1/me/live-heart-rate", json={"measured_at": measured_at, "heart_rate": 142}
        )

    alerts = ports.journal.of_kind(AlertKind.HIGH_HEART_RATE)
    assert len(alerts) == 1
    episode = alerts[0].episode
    assert episode is not None
    assert episode.max_heart_rate == HeartRate(142)
    assert ports.transaction.commits == 3
