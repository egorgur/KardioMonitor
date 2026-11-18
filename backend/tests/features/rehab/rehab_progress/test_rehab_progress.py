"""Слайс «Прогресс реабилитации»: через HTTP (ТР-05)."""

from datetime import datetime

from httpx import AsyncClient

from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from tests.features.monitoring.fakes import FakeActivityHistory
from tests.features.monitoring.minutes import minute
from tests.features.rehab.plans import MOSCOW, PATIENT

URL = f"/api/v1/patients/{PATIENT.id}/rehab-progress"


async def test_день_12_из_42_и_выполненная_норма_дня_3(
    client: AsyncClient, history: FakeActivityHistory
) -> None:
    start = datetime(2026, 10, 3, 10, tzinfo=MOSCOW)
    walk = [minute(i, steps=80, heart_rate=100, start=start) for i in range(30)]
    await history.save(PATIENT.id, MinuteBatch((*walk, minute(40, steps=8_000, start=start))))

    body = (await client.get(URL)).json()

    assert (body["program_day"], body["duration_days"]) == (12, 42)
    assert (body["met_days"], body["completed_days"]) == (1, 11)
    assert body["days"][2] == {"day": "2026-10-03", "program_day": 3, "status": "met"}
    assert body["days"][0]["status"] == "no_data"
    assert body["days"][-1]["status"] == "in_progress"
    assert body["current_stage"] == {"number": 1, "first_day": 1, "last_day": 42}
