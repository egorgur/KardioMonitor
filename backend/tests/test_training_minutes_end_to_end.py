"""Верхний порог на настоящей SQLite: сохранение, доставка, история и права врача."""

from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from httpx import AsyncClient

from kardiomonitor.features.alerts.domain.patient_alert import AlertKind
from kardiomonitor.shared_kernel import Clock
from tests.clinic import add_doctor, browser, register_phone, running_app, sign_in
from tests.fakes import FixedClock


@pytest.fixture
async def app(tmp_path: Path) -> AsyncIterator[FastAPI]:
    async with running_app(tmp_path) as app:
        yield app


def batch(start: str, count: int, *, steps: int = 80) -> dict[str, Any]:
    first = datetime.fromisoformat(start)
    return {
        "minutes": [
            {
                "started_at": (first + timedelta(minutes=i)).isoformat(),
                "steps": steps,
                "heart_rate": 100,
                "kind": "other",
                "kind_code": 80,
                "intensity": 0,
            }
            for i in range(count)
        ]
    }


async def set_threshold(
    doctor: AsyncClient,
    patient_id: str,
    threshold: int | None,
    *,
    zone: tuple[int, int] = (90, 110),
) -> None:
    plan = (await doctor.get(f"/api/v1/patients/{patient_id}/rehab-plan")).json()
    settings = plan["monitoring_settings"]
    settings["training_minutes_upper_threshold"] = threshold
    for stage in plan["program"]["stages"]:
        stage["targets"]["training_zone"] = {"lower_bpm": zone[0], "upper_bpm": zone[1]}
    response = await doctor.put(
        f"/api/v1/patients/{patient_id}/rehab-plan",
        json={
            "program": plan["program"],
            "monitoring_settings": settings,
        },
    )
    assert response.status_code == 200, response.text


async def training_feed(phone: AsyncClient, token: dict[str, str]) -> dict[str, Any]:
    response = await phone.get(
        "/api/v1/me/alerts",
        headers=token,
        params=[("supported_kinds", kind.value) for kind in AlertKind],
    )
    assert response.status_code == 200
    result: dict[str, Any] = response.json()
    return result


async def test_61_минута_доставляется_один_раз_и_просмотр_не_сбрасывается(
    app: FastAPI,
    tmp_path: Path,
) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone, time_zone="Europe/Moscow")
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        await set_threshold(doctor, patient_id, 60)
        for count in (59, 60):
            response = await phone.post(
                "/api/v1/me/activity-minutes",
                headers=token,
                json=batch("2026-10-16T08:00:00+03:00", count),
            )
            assert response.status_code == 200
            assert (await training_feed(phone, token))["alerts"] == []
        await phone.post(
            "/api/v1/me/activity-minutes", headers=token, json=batch("2026-10-16T09:00:00+03:00", 1)
        )
        first = await training_feed(phone, token)
        alert_id = first["alerts"][0]["alert_id"]
        await doctor.post(f"/api/v1/patients/{patient_id}/alerts/{alert_id}/viewed")
        for body in (
            batch("2026-10-16T09:00:00+03:00", 1),
            batch("2026-10-16T09:01:00+03:00", 1),
            batch("2026-10-16T08:00:00+03:00", 60, steps=0),
        ):
            assert (
                await phone.post("/api/v1/me/activity-minutes", headers=token, json=body)
            ).status_code == 200
        await set_threshold(doctor, patient_id, None)
        assert await training_feed(phone, token) == first
        assert first["alerts"][0]["training_minutes_exceeded"] == {
            "training_minutes": 61,
            "threshold_minutes": 60,
        }
        assert (await phone.get("/api/v1/me/alerts", headers=token)).json()["alerts"] == []
        alerts = (await doctor.get(f"/api/v1/patients/{patient_id}/alerts")).json()
        assert alerts[0]["is_viewed"] is True
        assert (await doctor.get("/api/v1/alerts/unviewed-counts")).json()[0][
            "unviewed_alerts"
        ] == 0


async def test_сохранение_плана_сразу_пересчитывает_сегодня_без_новой_загрузки(
    app: FastAPI,
    tmp_path: Path,
) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        await phone.post(
            "/api/v1/me/activity-minutes", headers=token, json=batch("2026-10-16T08:00:00Z", 61)
        )
        await set_threshold(doctor, patient_id, 60, zone=(120, 140))
        assert (await training_feed(phone, token))["alerts"] == []
        await set_threshold(doctor, patient_id, 100)
        assert (await training_feed(phone, token))["alerts"] == []
        await set_threshold(doctor, patient_id, 60)
        feed = await training_feed(phone, token)
        assert len(feed["alerts"]) == 1
        assert feed["alerts"][0]["training_minutes_exceeded"]["training_minutes"] == 61


async def test_поздний_пакет_через_полночь_использует_версии_дней_пациента(
    app: FastAPI,
    tmp_path: Path,
) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone, time_zone="Europe/Moscow")
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        await set_threshold(doctor, patient_id, 1)
        clock = await app.state.dishka_container.get(Clock)
        assert isinstance(clock, FixedClock)
        clock.current = datetime(2026, 10, 17, 9, tzinfo=UTC)
        await sign_in(doctor, "doctor.a")
        await set_threshold(doctor, patient_id, 100, zone=(120, 140))
        response = await phone.post(
            "/api/v1/me/activity-minutes", headers=token, json=batch("2026-10-16T23:58:00+03:00", 4)
        )
        assert response.status_code == 200
        feed = await training_feed(phone, token)
        assert [a["day"] for a in feed["alerts"]] == ["2026-10-16"]
        assert feed["alerts"][0]["training_minutes_exceeded"] == {
            "training_minutes": 2,
            "threshold_minutes": 1,
        }


async def test_чужой_врач_не_видит_превышение_и_не_может_изменить_порог(
    app: FastAPI,
    tmp_path: Path,
) -> None:
    async with browser(app) as phone, browser(app) as doctor, browser(app) as other:
        patient_id, token = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await add_doctor(tmp_path, "doctor.b")
        await sign_in(doctor, "doctor.a")
        await sign_in(other, "doctor.b")
        await set_threshold(doctor, patient_id, 1)
        await phone.post(
            "/api/v1/me/activity-minutes", headers=token, json=batch("2026-10-16T08:00:00Z", 2)
        )
        alert_id = (await training_feed(phone, token))["alerts"][0]["alert_id"]
        assert (await other.get("/api/v1/alerts")).json() == []
        assert (await other.get(f"/api/v1/patients/{patient_id}/alerts")).status_code == 404
        assert (
            await other.post(f"/api/v1/patients/{patient_id}/alerts/{alert_id}/viewed")
        ).status_code == 404
        body = (await doctor.get(f"/api/v1/patients/{patient_id}/rehab-plan")).json()
        assert (
            await other.put(
                f"/api/v1/patients/{patient_id}/rehab-plan",
                json={
                    "program": body["program"],
                    "monitoring_settings": body["monitoring_settings"],
                },
            )
        ).status_code == 404


async def test_пульс_сейчас_не_создаёт_превышение(app: FastAPI, tmp_path: Path) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        await set_threshold(doctor, patient_id, 1)
        for i in range(3):
            response = await phone.post(
                "/api/v1/me/live-heart-rate",
                headers=token,
                json={
                    "measured_at": f"2026-10-16T09:0{i}:00Z",
                    "heart_rate": 100,
                },
            )
            assert response.status_code == 204
        assert (await training_feed(phone, token))["alerts"] == []


async def test_исправление_без_новых_минут_создаёт_первое_превышение(
    app: FastAPI,
    tmp_path: Path,
) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        await set_threshold(doctor, patient_id, 1)
        await phone.post(
            "/api/v1/me/activity-minutes",
            headers=token,
            json=batch("2026-10-16T08:00:00Z", 2, steps=0),
        )
        assert (await training_feed(phone, token))["alerts"] == []
        response = await phone.post(
            "/api/v1/me/activity-minutes", headers=token, json=batch("2026-10-16T08:00:00Z", 2)
        )
        assert response.json()["new"] == 0
        assert (await training_feed(phone, token))["alerts"][0]["training_minutes_exceeded"] == {
            "training_minutes": 2,
            "threshold_minutes": 1,
        }


async def test_программа_включившая_сегодня_сразу_вызывает_проверку(
    app: FastAPI,
    tmp_path: Path,
) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        await set_threshold(doctor, patient_id, 1)
        url = f"/api/v1/patients/{patient_id}/rehab-plan"
        plan = (await doctor.get(url)).json()
        body = {"program": plan["program"], "monitoring_settings": plan["monitoring_settings"]}
        body["program"]["start_date"] = "2026-10-17"
        assert (await doctor.put(url, json=body)).status_code == 200
        await phone.post(
            "/api/v1/me/activity-minutes", headers=token, json=batch("2026-10-16T08:00:00Z", 2)
        )
        assert (await training_feed(phone, token))["alerts"] == []
        body["program"]["start_date"] = "2026-10-16"
        assert (await doctor.put(url, json=body)).status_code == 200
        assert len((await training_feed(phone, token))["alerts"]) == 1


async def test_перезапуск_и_повтор_пакета_не_создают_дубликат(tmp_path: Path) -> None:
    async with running_app(tmp_path) as app, browser(app) as phone, browser(app) as doctor:
        patient_id, token = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        await set_threshold(doctor, patient_id, 1)
        body = batch("2026-10-16T08:00:00Z", 2)
        await phone.post("/api/v1/me/activity-minutes", headers=token, json=body)
        before = await training_feed(phone, token)
    async with running_app(tmp_path) as restarted, browser(restarted) as phone:
        response = await phone.post("/api/v1/me/activity-minutes", headers=token, json=body)
        assert response.json()["new"] == 0
        assert await training_feed(phone, token) == before
