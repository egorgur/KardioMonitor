"""API запроса непрерывного пульса: врач → телефон → врач (ТР-09)."""

from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path
from typing import cast

import pytest
from httpx import AsyncClient, Response

from kardiomonitor.app.main import create_app
from kardiomonitor.shared_kernel import Clock
from tests.clinic import add_doctor, browser, register_phone, sign_in
from tests.database import database_url
from tests.fakes import FixedClock, replacing, settings_for_tests


@dataclass
class Scenario:
    phone: AsyncClient
    doctor: AsyncClient
    patient_id: str
    phone_headers: dict[str, str]
    clock: FixedClock


@pytest.fixture
async def scenario(tmp_path: Path) -> AsyncIterator[Scenario]:
    clock = FixedClock()
    app = create_app(settings_for_tests(database_url(tmp_path)), replacing((Clock, clock)))
    async with app.router.lifespan_context(app), browser(app) as phone, browser(app) as doctor:
        patient_id, headers = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        assert await sign_in(doctor, "doctor.a") == 200
        yield Scenario(phone, doctor, patient_id, headers, clock)


async def _create(scenario: Scenario, duration: int | None = None) -> dict[str, object]:
    body = {} if duration is None else {"duration_minutes": duration}
    response = await scenario.doctor.post(
        f"/api/v1/patients/{scenario.patient_id}/live-heart-rate-requests", json=body
    )
    assert response.status_code == 201, response.text
    return cast("dict[str, object]", response.json())


async def _report(
    scenario: Scenario,
    request_id: object,
    status: str,
    failure_reason: str | None = None,
) -> Response:
    return await scenario.phone.put(
        f"/api/v1/me/live-heart-rate-requests/{request_id}/status",
        headers=scenario.phone_headers,
        json={"status": status, "failure_reason": failure_reason},
    )


async def test_врач_создал_запрос_телефон_получил_его_при_синхронизации(
    scenario: Scenario,
) -> None:
    created = await _create(scenario)

    response = await scenario.phone.get(
        "/api/v1/me/live-heart-rate-request", headers=scenario.phone_headers
    )
    phone_request = response.json()["request"]

    assert created["duration_minutes"] == phone_request["duration_minutes"] == 10
    assert created["status"] == phone_request["status"] == "waiting_for_phone"
    assert created["patient_id"] == scenario.patient_id
    assert "patient_id" not in phone_request
    assert created["expires_at"] == "2026-10-16T11:30:00Z"


async def test_у_пациента_только_один_активный_запрос(scenario: Scenario) -> None:
    await _create(scenario, 7)

    duplicate = await scenario.doctor.post(
        f"/api/v1/patients/{scenario.patient_id}/live-heart-rate-requests",
        json={"duration_minutes": 12},
    )

    assert duplicate.status_code == 409
    assert duplicate.json()["code"] == "active_live_heart_rate_request"


async def test_телефон_сообщил_работу_и_завершение_панель_видит_состояние(
    scenario: Scenario,
) -> None:
    created = await _create(scenario)
    scenario.clock.current += timedelta(minutes=1)
    running = await _report(scenario, created["request_id"], "running")
    scenario.clock.current += timedelta(minutes=10)
    completed = await _report(scenario, created["request_id"], "completed")
    repeated = await _report(scenario, created["request_id"], "completed")
    latest = await scenario.doctor.get(
        f"/api/v1/patients/{scenario.patient_id}/live-heart-rate-requests/latest"
    )

    assert running.json()["started_at"] == "2026-10-16T09:31:00Z"
    assert completed.status_code == repeated.status_code == 200
    assert completed.json()["finished_at"] == repeated.json()["finished_at"]
    assert latest.json()["request"]["status"] == "completed"


async def test_пациент_остановил_идущий_запрос(scenario: Scenario) -> None:
    created = await _create(scenario)
    await _report(scenario, created["request_id"], "running")

    stopped = await _report(scenario, created["request_id"], "stopped_by_patient")

    assert stopped.status_code == 200
    assert stopped.json()["status"] == "stopped_by_patient"


async def test_просроченный_запрос_не_отдаётся_телефону_и_не_мешает_новому(
    scenario: Scenario,
) -> None:
    first = await _create(scenario)
    scenario.clock.current += timedelta(hours=3)

    active = await scenario.phone.get(
        "/api/v1/me/live-heart-rate-request", headers=scenario.phone_headers
    )
    latest = await scenario.doctor.get(
        f"/api/v1/patients/{scenario.patient_id}/live-heart-rate-requests/latest"
    )
    second = await _create(scenario, 15)

    assert active.json() == {"request": None}
    assert latest.json()["request"]["status"] == "failed"
    assert latest.json()["request"]["failure_reason"] == "expired"
    assert latest.json()["request"]["finished_at"] == first["expires_at"]
    assert second["request_id"] != first["request_id"]


async def test_нельзя_завершить_запрос_до_начала(scenario: Scenario) -> None:
    created = await _create(scenario)

    response = await _report(scenario, created["request_id"], "completed")

    assert response.status_code == 409
    assert response.json()["code"] == "invalid_live_heart_rate_request_transition"


async def test_телефон_не_может_изменить_чужой_запрос(scenario: Scenario) -> None:
    created = await _create(scenario)
    _, other_headers = await register_phone(scenario.phone, "Другой")

    response = await scenario.phone.put(
        f"/api/v1/me/live-heart-rate-requests/{created['request_id']}/status",
        headers=other_headers,
        json={"status": "running", "failure_reason": None},
    )

    assert response.status_code == 404
    assert response.json()["code"] == "live_heart_rate_request_not_found"


@pytest.mark.parametrize("duration", [0, 61])
async def test_api_не_принимает_длительность_вне_диапазона(
    scenario: Scenario, duration: int
) -> None:
    response = await scenario.doctor.post(
        f"/api/v1/patients/{scenario.patient_id}/live-heart-rate-requests",
        json={"duration_minutes": duration},
    )

    assert response.status_code == 422
    assert response.json()["code"] == "invalid_request"
