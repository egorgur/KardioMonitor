"""Слайс «Выполнение нормы дня»: через HTTP (ТР-02, ТР-03)."""

from datetime import datetime, timedelta

from httpx import AsyncClient

from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from tests.features.monitoring.fakes import FakeActivityHistory
from tests.features.monitoring.minutes import minute
from tests.features.rehab.plans import MOSCOW, PATIENT


def _url(day: str) -> str:
    return f"/api/v1/patients/{PATIENT.id}/days/{day}/target-progress"


async def _walk(history: FakeActivityHistory, start: datetime, minutes: int) -> None:
    walk = tuple(minute(i, steps=80, heart_rate=100, start=start) for i in range(minutes))
    await history.save(PATIENT.id, MinuteBatch(walk))


async def test_завершённый_день_15_из_30_минут_норма_не_выполнена(
    client: AsyncClient, history: FakeActivityHistory
) -> None:
    await _walk(history, datetime(2026, 10, 5, 10, tzinfo=MOSCOW), 15)

    body = (await client.get(_url("2026-10-05"))).json()

    assert body["program_day"] == 5
    assert body["progress"]["training_minutes"] == 15
    assert body["progress"]["targets"]["training_minutes"] == 30
    assert body["progress"]["is_final"] is True
    assert body["progress"]["is_met"] is False


async def test_сегодняшний_итог_пока_не_окончательный(
    client: AsyncClient, history: FakeActivityHistory
) -> None:
    await _walk(history, datetime(2026, 10, 12, 8, tzinfo=MOSCOW), 5)

    body = (await client.get(_url("2026-10-12"))).json()

    assert body["progress"]["is_final"] is False


async def test_минута_после_полуночи_по_поясу_пациента_в_следующем_дне(
    client: AsyncClient, history: FakeActivityHistory
) -> None:
    await _walk(history, datetime(2026, 10, 5, 23, 59, tzinfo=MOSCOW), 2)

    day5 = (await client.get(_url("2026-10-05"))).json()
    day6 = (await client.get(_url("2026-10-06"))).json()

    assert (day5["progress"]["training_minutes"], day6["progress"]["training_minutes"]) == (1, 1)


async def test_день_вне_программы_без_нормы(client: AsyncClient) -> None:
    before_start = (datetime(2026, 10, 1, tzinfo=MOSCOW) - timedelta(days=1)).date()

    body = (await client.get(_url(before_start.isoformat()))).json()

    assert body == {"day": "2026-09-30", "program_day": None, "progress": None}
