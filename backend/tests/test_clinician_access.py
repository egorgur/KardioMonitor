"""Аккаунты врачей и доступ к пациентам — критерии приёмки ТР-11, на настоящей SQLite."""

import asyncio
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any
from uuid import uuid4

import pytest
from fastapi import FastAPI
from httpx import AsyncClient

from kardiomonitor.app.admin import main as admin
from tests.clinic import (
    DOCTOR_PASSWORD,
    add_doctor,
    browser,
    register_phone,
    running_app,
    sign_in,
)
from tests.database import database_url


@pytest.fixture
async def app(tmp_path: Path) -> AsyncIterator[FastAPI]:
    async with running_app(tmp_path) as app:
        yield app


def _patient_routes(app: FastAPI) -> list[tuple[str, str]]:
    """Все маршруты панели с `{patient_id}` из схемы OpenAPI: новый эндпоинт попадает сюда сам."""
    return [
        (method.upper(), path)
        for path, operations in app.openapi()["paths"].items()
        if "{patient_id}" in path
        for method in operations
    ]


async def test_врач_видит_только_своих_пациентов(app: FastAPI, tmp_path: Path) -> None:
    async with browser(app) as phone, browser(app) as doctor:
        own, _ = await register_phone(phone, "Свой")
        await register_phone(phone, "Чужой")
        await add_doctor(tmp_path, "doctor.a", own)
        await sign_in(doctor, "doctor.a")

        patients = (await doctor.get("/api/v1/patients")).json()

    assert [patient["patient_id"] for patient in patients] == [own]


async def test_чужой_пациент_по_любому_адресу_панели_не_найден(
    app: FastAPI, tmp_path: Path
) -> None:
    async with browser(app) as phone, browser(app) as doctor_a, browser(app) as doctor_b:
        patient_of_a, _ = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_of_a)
        await add_doctor(tmp_path, "doctor.b")
        await sign_in(doctor_a, "doctor.a")
        await sign_in(doctor_b, "doctor.b")
        bodies = await _valid_bodies(doctor_a, patient_of_a)
        statuses = {}
        for method, path in _patient_routes(app):
            url = (
                path.replace("{patient_id}", patient_of_a)
                .replace("{day}", "2026-10-16")
                .replace("{alert_id}", str(uuid4()))
            )
            body = bodies.get((method, path))
            statuses[method, path] = (await doctor_b.request(method, url, json=body)).status_code

    assert len(statuses) >= 6, "маршрутов с {patient_id} меньше, чем есть сейчас"
    assert set(statuses.values()) == {404}, (
        f"{statuses}: 422 — добавьте образец тела в _valid_bodies, 200 — нет проверки врача"
    )


async def _valid_bodies(doctor: AsyncClient, patient_id: str) -> dict[tuple[str, str], Any]:
    """Верные тела запросов с телом: иначе ответ 422 придёт раньше проверки доступа."""
    plan = (await doctor.get(f"/api/v1/patients/{patient_id}/rehab-plan")).json()
    change = {"program": plan["program"], "monitoring_settings": plan["monitoring_settings"]}
    return {
        ("PUT", "/api/v1/patients/{patient_id}/rehab-plan"): change,
        ("POST", "/api/v1/patients/{patient_id}/live-heart-rate-requests"): {},
    }


async def test_запрос_панели_без_сеанса_401(app: FastAPI) -> None:
    async with browser(app) as anonymous:
        response = await anonymous.get("/api/v1/patients")

    assert response.status_code == 401
    assert response.json()["code"] == "session_required"


async def test_после_выхода_сеанс_не_действует(app: FastAPI, tmp_path: Path) -> None:
    async with browser(app) as doctor:
        await add_doctor(tmp_path, "doctor.a")
        await sign_in(doctor, "doctor.a")
        stolen_cookie = doctor.cookies.get("kardio_session")

        signed_out = await doctor.post("/api/v1/auth/sign-out")
    async with browser(app) as attacker:
        attacker.cookies.set(
            "kardio_session", stolen_cookie or "", domain="clinic.test", path="/api"
        )
        replay = await attacker.get("/api/v1/auth/me")

    assert stolen_cookie
    assert signed_out.status_code == 204
    assert replay.status_code == 401


async def test_вход_ставит_защищённую_cookie_и_показывает_врача(
    app: FastAPI, tmp_path: Path
) -> None:
    async with browser(app) as doctor:
        await add_doctor(tmp_path, "doctor.a")
        response = await doctor.post(
            "/api/v1/auth/sign-in", json={"login": "Doctor.A", "password": DOCTOR_PASSWORD}
        )
        me = await doctor.get("/api/v1/auth/me")

    cookie = response.headers["set-cookie"].lower()
    assert all(flag in cookie for flag in ("httponly", "secure", "samesite=strict", "path=/api"))
    assert me.json()["login"] == "doctor.a"


async def test_неверный_пароль_5_раз_подряд_вход_временно_недоступен(
    app: FastAPI, tmp_path: Path
) -> None:
    async with browser(app) as doctor:
        await add_doctor(tmp_path, "doctor.a")
        failures = [await sign_in(doctor, "doctor.a", "неверный пароль") for _ in range(5)]
        locked = await sign_in(doctor, "doctor.a")

    assert failures == [401] * 5
    assert locked == 429


async def test_неизвестный_логин_отвечает_как_неверный_пароль(app: FastAPI) -> None:
    async with browser(app) as doctor:
        response = await doctor.post(
            "/api/v1/auth/sign-in", json={"login": "nobody", "password": DOCTOR_PASSWORD}
        )

    assert response.status_code == 401
    assert response.json()["code"] == "invalid_credentials"


async def test_администратор_передал_пациента_от_а_к_б(app: FastAPI, tmp_path: Path) -> None:
    async with browser(app) as phone, browser(app) as doctor_a, browser(app) as doctor_b:
        patient_id, _ = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await add_doctor(tmp_path, "doctor.b", patient_id)
        await sign_in(doctor_a, "doctor.a")
        await sign_in(doctor_b, "doctor.b")

        of_a = (await doctor_a.get("/api/v1/patients")).json()
        of_b = (await doctor_b.get("/api/v1/patients")).json()

    assert of_a == []
    assert [patient["patient_id"] for patient in of_b] == [patient_id]


async def test_команда_администратора_показывает_пациентов_без_врача(
    app: FastAPI, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    async with browser(app) as phone:
        patient_id, _ = await register_phone(phone, "Без врача")
    monkeypatch.setenv("KARDIO_DATABASE_URL", database_url(tmp_path))
    output: list[str] = []

    code = await asyncio.to_thread(admin, ["patients"], output.append)

    assert code == 0
    assert output == [f"{patient_id}  Без врача  врач: —\n"]


async def test_вход_после_неудачных_попыток_успешен(app: FastAPI, tmp_path: Path) -> None:
    async with browser(app) as doctor:
        await add_doctor(tmp_path, "doctor.a")
        failures = [await sign_in(doctor, "doctor.a", "неверный пароль") for _ in range(2)]

        signed_in = await sign_in(doctor, "doctor.a")

    assert failures == [401, 401]
    assert signed_in == 200
