"""Слайс «Прогресс пациентов врача»: по кешу итогов дня, тот же итог, что и по минутам (ТР-06)."""

from datetime import date, datetime

from httpx import AsyncClient

from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from tests.fakes import FakeTransaction
from tests.features.monitoring.fakes import FakeActivityHistory
from tests.features.monitoring.minutes import minute
from tests.features.rehab.fakes import FakeDayOutcomes
from tests.features.rehab.plans import MOSCOW, PATIENT

URL = "/api/v1/rehab-progress"


async def _walk(history: FakeActivityHistory, day: int, minutes: int, steps: int = 400) -> None:
    start = datetime(2026, 10, day, 10, tzinfo=MOSCOW)
    walk = tuple(minute(i, steps=steps, heart_rate=100, start=start) for i in range(minutes))
    await history.save(PATIENT.id, MinuteBatch(walk))


async def test_прогресс_в_списке_совпадает_с_прогрессом_в_карточке(
    client: AsyncClient, history: FakeActivityHistory
) -> None:
    for day in (2, 3, 5):
        await _walk(history, day, 30)
    await _walk(history, 4, 10)
    await _walk(history, 12, 15)

    row = (await client.get(URL)).json()[0]
    card = (await client.get(f"/api/v1/patients/{PATIENT.id}/rehab-progress")).json()

    assert (row["met_days"], row["completed_days"]) == (card["met_days"], card["completed_days"])
    assert (row["met_days"], row["completed_days"], row["program_day"]) == (3, 11, 12)
    assert row["today"] == {
        "training_minutes": 15,
        "target_training_minutes": 30,
        "steps": 6000,
        "target_steps": 10000,
        "is_met": False,
    }


async def test_итоги_прошедших_дней_записываются_в_кеш(
    client: AsyncClient,
    history: FakeActivityHistory,
    outcomes: FakeDayOutcomes,
    transaction: FakeTransaction,
) -> None:
    await _walk(history, 2, 30)

    await client.get(URL)

    cached = {day for owner, day in outcomes.outcomes if owner == PATIENT.id}
    assert cached == {date(2026, 10, day) for day in range(1, 13)}
    assert outcomes.outcomes[PATIENT.id, date(2026, 10, 2)].is_met
    assert transaction.commits == 1


async def test_правка_плана_сбрасывает_кеш_и_дни_оцениваются_заново(
    client: AsyncClient, history: FakeActivityHistory
) -> None:
    await _walk(history, 12, 20, steps=600)
    await client.get(URL)
    plan_url = f"/api/v1/patients/{PATIENT.id}/rehab-plan"
    plan = (await client.get(plan_url)).json()
    plan["program"]["stages"][0]["targets"]["training_minutes"] = 20
    plan["program"]["stages"][0]["targets"]["steps"] = 12_000

    await client.put(
        plan_url,
        json={"program": plan["program"], "monitoring_settings": plan["monitoring_settings"]},
    )
    row = (await client.get(URL)).json()[0]

    assert row["today"]["target_training_minutes"] == 20
    assert row["today"]["is_met"] is True
