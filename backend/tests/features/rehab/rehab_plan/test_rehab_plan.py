"""Слайс «План реабилитации»: через HTTP, порты подменены фейками (ТР-01)."""

from datetime import date
from typing import Any
from uuid import uuid4

from httpx import AsyncClient

from kardiomonitor.features.access.domain.access_log import AccessAction
from tests.fakes import FakeTransaction
from tests.features.access.fakes import FakeAccessLog
from tests.features.rehab.fakes import FakeRehabPlans
from tests.features.rehab.plans import PATIENT

URL = f"/api/v1/patients/{PATIENT.id}/rehab-plan"


def _stage(
    first: int, last: int, minutes: int = 30, zone: tuple[int, int] = (90, 110)
) -> dict[str, Any]:
    targets = {
        "training_zone": {"lower_bpm": zone[0], "upper_bpm": zone[1]},
        "training_minutes": minutes,
        "steps": 10_000,
    }
    return {"first_day": first, "last_day": last, "targets": targets}


def _plan(*stages: dict[str, Any], days: int = 25) -> dict[str, Any]:
    return {
        "program": {"start_date": "2026-10-01", "duration_days": days, "stages": list(stages)},
        "monitoring_settings": {
            "walking_steps_threshold": 60,
            "high_heart_rate_threshold": 130,
            "low_activity_steps": 1000,
            "daily_alert_time_utc": "17:00:00",
        },
    }


async def test_новому_пациенту_программа_по_умолчанию(client: AsyncClient) -> None:
    body = (await client.get(URL)).json()

    assert body["is_default"] is True
    assert body["program"]["stages"] == [_stage(1, 42)]
    assert body["monitoring_settings"]["walking_steps_threshold"] == 60
    assert body["time_zone"] == "Europe/Moscow"


async def test_правка_действует_с_сегодняшнего_дня_пациента(
    client: AsyncClient, plans: FakeRehabPlans, transaction: FakeTransaction
) -> None:
    response = await client.put(URL, json=_plan(_stage(1, 15), _stage(16, 25, minutes=40)))

    assert response.status_code == 200
    assert response.json()["effective_from"] == "2026-10-12"
    assert response.json()["is_default"] is False
    assert transaction.commits == 1
    saved = plans.plans[PATIENT.id]
    assert saved.targets_on(date(2026, 10, 5)) is not None
    assert [r.effective_from for r in saved.revisions] == [date(2026, 10, 1), date(2026, 10, 12)]


async def test_этапы_с_пропуском_дней_отклоняются_и_план_не_меняется(
    client: AsyncClient, plans: FakeRehabPlans, transaction: FakeTransaction
) -> None:
    response = await client.put(URL, json=_plan(_stage(1, 15), _stage(18, 25)))

    assert response.status_code == 422
    assert response.json()["code"] == "invalid_program_stages"
    assert "дни 16–17 не входят ни в один этап" in response.json()["detail"]
    assert transaction.commits == 0
    assert plans.plans == {}


async def test_зона_120_100_отклоняется(client: AsyncClient) -> None:
    response = await client.put(URL, json=_plan(_stage(1, 25, zone=(120, 100))))

    assert response.json()["code"] == "invalid_training_zone"


async def test_план_неизвестного_пациента_не_найден(client: AsyncClient) -> None:
    response = await client.get(f"/api/v1/patients/{uuid4()}/rehab-plan")

    assert response.status_code == 404


async def test_открытие_и_правка_плана_записываются_в_журнал_доступа(
    client: AsyncClient, access_log: FakeAccessLog
) -> None:
    await client.get(URL)
    await client.put(URL, json=_plan(_stage(1, 25)))

    assert [(r.action, r.subject_id) for r in access_log.records] == [
        (AccessAction.VIEWED_REHAB_PLAN, PATIENT.id),
        (AccessAction.CHANGED_REHAB_PLAN, PATIENT.id),
    ]


async def test_старый_put_сохраняет_порог_а_null_отключает(client: AsyncClient) -> None:
    body = _plan(_stage(1, 25))
    assert (await client.get(URL)).json()["monitoring_settings"][
        "training_minutes_upper_threshold"
    ] is None
    body["monitoring_settings"]["training_minutes_upper_threshold"] = 60
    assert (await client.put(URL, json=body)).status_code == 200
    saved = (await client.put(URL, json=_plan(_stage(1, 25)))).json()
    assert saved["monitoring_settings"]["training_minutes_upper_threshold"] == 60
    body["monitoring_settings"]["training_minutes_upper_threshold"] = None
    disabled = (await client.put(URL, json=body)).json()
    assert disabled["monitoring_settings"]["training_minutes_upper_threshold"] is None


async def test_неверный_верхний_порог_не_сохраняется(
    client: AsyncClient,
    transaction: FakeTransaction,
) -> None:
    body = _plan(_stage(1, 25))
    for threshold in (0, 1441, True, 60.5, "60"):
        body["monitoring_settings"]["training_minutes_upper_threshold"] = threshold
        assert (await client.put(URL, json=body)).status_code == 422
    assert transaction.commits == 0
