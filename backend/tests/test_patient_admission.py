"""Создание пациента клиникой, привязка телефона по коду, поиск и прогресс на главной —
критерии приёмки ТР-07 и ТР-06, на настоящей SQLite. Данные вымышленные."""

import logging
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
from fastapi import FastAPI
from httpx import AsyncClient

from tests.clinic import add_doctor, admission, browser, running_app, sign_in


@pytest.fixture
async def app(tmp_path: Path) -> AsyncIterator[FastAPI]:
    async with running_app(tmp_path) as app:
        yield app


@pytest.fixture
async def doctor(app: FastAPI, tmp_path: Path) -> AsyncIterator[AsyncClient]:
    async with browser(app) as client:
        await add_doctor(tmp_path, "doctor.a")
        await sign_in(client, "doctor.a")
        yield client


async def test_врач_создал_пациента_он_в_списке_с_программой_и_кодом_привязки(
    doctor: AsyncClient,
) -> None:
    created = await doctor.post("/api/v1/patients", json=admission())
    patient_id = created.json()["patient_id"]

    patients = (await doctor.get("/api/v1/patients")).json()
    card = (await doctor.get(f"/api/v1/patients/{patient_id}/profile")).json()
    plan = (await doctor.get(f"/api/v1/patients/{patient_id}/rehab-plan")).json()

    assert created.status_code == 201
    assert len(created.json()["enrollment_code"]) >= 20
    assert [p["name"] for p in patients] == ["Тестов Пётр Иванович"]
    assert card["profile"]["diagnosis"] == "вымышленный диагноз"
    assert card["profile"]["age_years"] == 65
    assert plan["is_default"] is True


async def test_телефон_привязывается_по_коду_один_раз_и_шлёт_данные(
    app: FastAPI, doctor: AsyncClient
) -> None:
    created = (await doctor.post("/api/v1/patients", json=admission())).json()
    async with browser(app) as phone:
        enrolled = await phone.post("/api/v1/enroll", json={"code": created["enrollment_code"]})
        again = await phone.post("/api/v1/enroll", json={"code": created["enrollment_code"]})
        token = {"Authorization": f"Bearer {enrolled.json()['token']}"}
        me = await phone.get("/api/v1/me", headers=token)

    assert enrolled.status_code == 201
    assert enrolled.json()["patient_id"] == created["patient_id"]
    assert again.json()["code"] == "invalid_enrollment_code"
    assert me.json()["patient_id"] == created["patient_id"]


async def test_новый_код_привязки_отменяет_прежний(app: FastAPI, doctor: AsyncClient) -> None:
    created = (await doctor.post("/api/v1/patients", json=admission())).json()
    url = f"/api/v1/patients/{created['patient_id']}/enrollment-code"
    new_code = (await doctor.post(url)).json()["enrollment_code"]
    async with browser(app) as phone:
        old = await phone.post("/api/v1/enroll", json={"code": created["enrollment_code"]})
        new = await phone.post("/api/v1/enroll", json={"code": new_code})

    assert old.status_code == 422
    assert new.status_code == 201


@pytest.mark.parametrize(
    ("overrides", "code", "message"),
    [
        ({"birth_date": "2030-01-01"}, "invalid_birth_date", "дата рождения в будущем"),
        ({"last_name": ""}, "invalid_full_name", "укажите фамилию"),
        ({"height_cm": 40}, "invalid_body_parameters", "рост"),
        ({"time_zone": "Mars/Base"}, "unknown_time_zone", "часовой пояс"),
    ],
)
async def test_неверная_карточка_отклоняется_и_пациент_не_создан(
    doctor: AsyncClient, overrides: dict[str, object], code: str, message: str
) -> None:
    response = await doctor.post("/api/v1/patients", json=admission(**overrides))

    assert response.status_code == 422
    assert response.json()["code"] == code
    assert message in response.json()["detail"]
    assert (await doctor.get("/api/v1/patients")).json() == []


async def test_в_журнале_сервера_нет_фио_даты_рождения_и_диагноза(
    doctor: AsyncClient, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.DEBUG)
    caplog.set_level(logging.INFO, logger="sqlalchemy.engine")  # как при echo=True

    created = (await doctor.post("/api/v1/patients", json=admission())).json()
    await doctor.get(f"/api/v1/patients/{created['patient_id']}/profile")
    await doctor.post("/api/v1/patients", json=admission(last_name="Тестов" * 10))  # ошибка

    logged = caplog.text
    assert not any(
        value in logged for value in ("Тестов", "Пётр", "1961-04-12", "вымышленный диагноз")
    )


@pytest.mark.parametrize(
    ("query", "found"),
    [("иван", ["Иванов Пётр", "Иванова Анна", "Петров Иван"]), ("семенов", ["Семёнов Олег"])],
)
async def test_поиск_по_фио_без_учёта_регистра_и_ё(
    doctor: AsyncClient, query: str, found: list[str]
) -> None:
    for last, first in [
        ("Иванов", "Пётр"),
        ("Иванова", "Анна"),
        ("Петров", "Иван"),
        ("Семёнов", "Олег"),
    ]:
        await doctor.post(
            "/api/v1/patients", json=admission(last_name=last, first_name=first, middle_name=None)
        )

    names = [p["name"] for p in (await doctor.get(f"/api/v1/patients?search={query}")).json()]

    assert names == found


async def test_главная_получает_прогресс_каждого_пациента_врача(doctor: AsyncClient) -> None:
    created = (await doctor.post("/api/v1/patients", json=admission())).json()

    rows = (await doctor.get("/api/v1/rehab-progress")).json()

    assert [row["patient_id"] for row in rows] == [created["patient_id"]]
    assert (rows[0]["program_day"], rows[0]["duration_days"]) == (1, 42)
    assert rows[0]["today"]["target_training_minutes"] == 30
