"""Сквозной сценарий демо на настоящей SQLite: телефон регистрируется и присылает данные,
врач видит их на панели."""

from collections.abc import AsyncIterator
from datetime import timedelta
from pathlib import Path

import pytest
from fastapi import FastAPI

from tests.clinic import add_doctor, browser, register_phone, running_app, sign_in
from tests.fakes import NOW


@pytest.fixture
async def app(tmp_path: Path) -> AsyncIterator[FastAPI]:
    async with running_app(tmp_path) as app:
        yield app


async def test_пульс_и_шаги_с_телефона_видны_врачу_в_дне_пациента(
    app: FastAPI, tmp_path: Path
) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone, "Иван П.", "Asia/Novosibirsk")
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        minutes = [
            {"started_at": "2026-10-15T23:59:00+07:00", "steps": 8, "heart_rate": 75,
             "kind": "other", "kind_code": 80, "intensity": 10},
            {"started_at": "2026-10-16T00:00:00+07:00", "steps": 3, "heart_rate": None,
             "kind": "not_worn", "intensity": 0},
        ]  # fmt: skip

        upload = await phone.post(
            "/api/v1/me/activity-minutes", json={"minutes": minutes}, headers=token
        )
        cursor = await phone.get("/api/v1/me/activity-minutes/last", headers=token)
        day = await doctor.get(f"/api/v1/patients/{patient_id}/days/2026-10-15")

    assert upload.json()["new"] == 2
    assert cursor.json() == {"started_at": "2026-10-15T17:00:00Z"}
    assert day.json()["summary"]["steps"] == 8
    assert {"from_bpm": 70, "to_bpm": 79, "minutes": 1} in day.json()["heart_rate_distribution"]
    assert [m["started_at"] for m in day.json()["minutes"]] == ["2026-10-15T16:59:00Z"]


async def test_пульс_сейчас_с_телефона_виден_врачу(app: FastAPI, tmp_path: Path) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        measured_at = (NOW - timedelta(seconds=5)).isoformat()

        sent = await phone.post(
            "/api/v1/me/live-heart-rate",
            json={"measured_at": measured_at, "heart_rate": 91},
            headers=token,
        )
        window = await doctor.get(
            f"/api/v1/patients/{patient_id}/live-heart-rate?window_seconds=60"
        )
        patients = await doctor.get("/api/v1/patients")

    assert sent.status_code == 204
    assert [r["heart_rate"] for r in window.json()["readings"]] == [91]
    assert patients.json()[0]["live_heart_rate"] == 91


async def test_врач_меняет_план_реабилитации_телефон_получает_нормы(
    app: FastAPI, tmp_path: Path
) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        plan_url = f"/api/v1/patients/{patient_id}/rehab-plan"
        plan = (await doctor.get(plan_url)).json()
        plan["program"]["stages"][0]["targets"]["training_minutes"] = 45

        changed = await doctor.put(
            plan_url,
            json={"program": plan["program"], "monitoring_settings": plan["monitoring_settings"]},
        )
        progress = await doctor.get(f"/api/v1/patients/{patient_id}/rehab-progress")
        on_phone = await phone.get("/api/v1/me/rehab-plan", headers=token)

    assert changed.status_code == 200
    assert progress.json()["program_day"] == 1
    assert on_phone.json()["program"]["stages"][0]["targets"]["training_minutes"] == 45
