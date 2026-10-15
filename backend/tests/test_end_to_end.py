"""Сквозной сценарий демо на настоящей SQLite: телефон регистрируется и присылает данные,
панель клиники их видит."""

from collections.abc import AsyncIterator
from datetime import timedelta
from pathlib import Path

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr

from kardiomonitor.app.main import create_app
from kardiomonitor.shared_kernel import Clock
from tests.database import database_url
from tests.fakes import NOW, FixedClock, replacing, settings_for_tests


async def _client(app: FastAPI) -> AsyncIterator[AsyncClient]:
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        yield client


@pytest.fixture
async def client(tmp_path: Path) -> AsyncIterator[AsyncClient]:
    app = create_app(settings_for_tests(database_url(tmp_path)), replacing((Clock, FixedClock())))
    async for client in _client(app):
        yield client


async def test_пульс_и_шаги_с_телефона_видны_на_панели_в_дне_пациента(client: AsyncClient) -> None:
    registration = await client.post(
        "/api/v1/register", json={"name": "Иван П.", "time_zone": "Asia/Novosibirsk"}
    )
    patient_id, token = registration.json()["patient_id"], registration.json()["token"]
    phone = {"Authorization": f"Bearer {token}"}
    minutes = [
        {"started_at": "2026-10-15T23:59:00+07:00", "steps": 8, "heart_rate": 75,
         "kind": "other", "kind_code": 80, "intensity": 10},
        {"started_at": "2026-10-16T00:00:00+07:00", "steps": 3, "heart_rate": None,
         "kind": "not_worn", "intensity": 0},
    ]  # fmt: skip

    upload = await client.post(
        "/api/v1/me/activity-minutes", json={"minutes": minutes}, headers=phone
    )
    cursor = await client.get("/api/v1/me/activity-minutes/last", headers=phone)
    day = await client.get(f"/api/v1/patients/{patient_id}/days/2026-10-15")

    assert upload.json()["new"] == 2
    assert cursor.json() == {"started_at": "2026-10-15T17:00:00Z"}
    assert day.json()["summary"]["steps"] == 8
    assert [m["started_at"] for m in day.json()["minutes"]] == ["2026-10-15T16:59:00Z"]


async def test_пульс_сейчас_с_телефона_виден_на_панели(client: AsyncClient) -> None:
    registration = await client.post("/api/v1/register", json={"name": "Анна", "time_zone": "UTC"})
    patient_id, token = registration.json()["patient_id"], registration.json()["token"]
    measured_at = (NOW - timedelta(seconds=5)).isoformat()

    sent = await client.post(
        "/api/v1/me/live-heart-rate",
        json={"measured_at": measured_at, "heart_rate": 91},
        headers={"Authorization": f"Bearer {token}"},
    )
    window = await client.get(f"/api/v1/patients/{patient_id}/live-heart-rate?window_seconds=60")
    patients = await client.get("/api/v1/patients")

    assert sent.status_code == 204
    assert [r["heart_rate"] for r in window.json()["readings"]] == [91]
    assert patients.json()[0]["live_heart_rate"] == 91


async def test_с_ключом_панели_её_данные_без_ключа_не_отдаются(tmp_path: Path) -> None:
    settings = settings_for_tests(database_url(tmp_path)).model_copy(
        update={"dashboard_key": SecretStr("demo-secret")}
    )
    async for client in _client(create_app(settings)):
        without = await client.get("/api/v1/patients")
        with_key = await client.get("/api/v1/patients", headers={"X-Dashboard-Key": "demo-secret"})

        assert without.status_code == 401
        assert with_key.status_code == 200
