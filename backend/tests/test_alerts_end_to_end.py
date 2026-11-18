"""Оповещения ТР-08 на настоящей SQLite: телефон присылает данные — врач видит оповещение,
отмечает его, телефон получает его при синхронизации."""

from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from dishka import AsyncContainer
from fastapi import FastAPI

from kardiomonitor.features.alerts.daily_check import run_daily_check
from kardiomonitor.shared_kernel import Clock
from tests.clinic import add_doctor, browser, register_phone, running_app, sign_in
from tests.fakes import FixedClock


@pytest.fixture
async def app(tmp_path: Path) -> AsyncIterator[FastAPI]:
    async with running_app(tmp_path) as app:
        yield app


def _minutes(start: str, *heart_rates: int) -> dict[str, Any]:
    first = datetime.fromisoformat(start)
    return {
        "minutes": [
            {"started_at": (first + timedelta(minutes=i)).isoformat(), "steps": 20,
             "heart_rate": bpm, "kind": "other", "kind_code": 80, "intensity": 10}
            for i, bpm in enumerate(heart_rates)
        ]
    }  # fmt: skip


async def test_высокий_пульс_виден_врачу_и_телефону_повтор_не_дублирует(
    app: FastAPI, tmp_path: Path
) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone, "Анна", "Europe/Moscow")
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        batch = _minutes("2026-10-16T10:01:00+03:00", 135, 140, 138, 100, 100, 131)

        await phone.post("/api/v1/me/activity-minutes", json=batch, headers=token)
        await phone.post("/api/v1/me/activity-minutes", json=batch, headers=token)
        alerts = (await doctor.get("/api/v1/alerts")).json()
        counts = (await doctor.get("/api/v1/alerts/unviewed-counts")).json()
        await doctor.post(f"/api/v1/patients/{patient_id}/alerts/{alerts[0]['alert_id']}/viewed")
        counts_after = (await doctor.get("/api/v1/alerts/unviewed-counts")).json()
        feed = (await phone.get("/api/v1/me/alerts", headers=token)).json()

    assert [alert["kind"] for alert in alerts] == ["high_heart_rate"]
    assert alerts[0]["high_heart_rate"]["max_heart_rate"] == 140
    assert alerts[0]["high_heart_rate"]["duration_minutes"] == 6
    assert counts == [{"patient_id": patient_id, "unviewed_alerts": 1}]
    assert counts_after == [{"patient_id": patient_id, "unviewed_alerts": 0}]
    assert [alert["alert_id"] for alert in feed["alerts"]] == [alerts[0]["alert_id"]]


async def test_дневная_проверка_в_1700_utc_создаёт_одно_оповещение_дня(
    app: FastAPI, tmp_path: Path
) -> None:
    container: AsyncContainer = app.state.dishka_container
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone, "Анна", "Europe/Moscow")
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        await phone.post(
            "/api/v1/me/activity-minutes",
            json=_minutes("2026-10-16T13:00:00+03:00", *[100] * 12),
            headers=token,
        )
        clock = await container.get(Clock)
        assert isinstance(clock, FixedClock)
        clock.current = datetime(2026, 10, 16, 17, 0, 30, tzinfo=UTC)

        await run_daily_check(container)
        await run_daily_check(container)
        alerts = (await doctor.get(f"/api/v1/patients/{patient_id}/alerts")).json()
        feed = (await phone.get("/api/v1/me/alerts", headers=token)).json()

    assert sorted(alert["kind"] for alert in alerts) == ["daily_target_missed", "low_activity"]
    missed = next(alert for alert in alerts if alert["kind"] == "daily_target_missed")
    assert missed["day"] == "2026-10-16"
    assert missed["daily_target"]["steps"] == 240
    assert len(feed["alerts"]) == 2


async def test_список_пациентов_показывает_норму_сегодня_по_принятым_минутам(
    app: FastAPI, tmp_path: Path
) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone, "Анна", "Europe/Moscow")
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        before = (await doctor.get("/api/v1/rehab-progress")).json()

        await phone.post(
            "/api/v1/me/activity-minutes",
            json=_minutes("2026-10-16T08:00:00+03:00", *[100] * 5),
            headers=token,
        )
        after = (await doctor.get("/api/v1/rehab-progress")).json()

    assert before[0]["today"]["steps"] == 0
    assert after[0]["today"]["steps"] == 100
