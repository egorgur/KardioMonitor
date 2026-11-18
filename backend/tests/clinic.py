"""Сквозные тесты: сервер на настоящей SQLite, телефон и врачи с настоящим Argon2id."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from uuid import UUID

from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from kardiomonitor.app.admin import assign_patient, create_clinician
from kardiomonitor.app.main import create_app
from kardiomonitor.features.access.data.argon2_password_hasher import Argon2PasswordHasher
from kardiomonitor.features.access.domain.clinician import ClinicianName, Login
from kardiomonitor.features.access.domain.password import Password
from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.shared_kernel import Clock
from tests.database import database_url
from tests.fakes import FixedClock, replacing, settings_for_tests

DOCTOR_PASSWORD = "вымышленный пароль врача"  # noqa: S105 — тестовые данные


@asynccontextmanager
async def running_app(directory: Path) -> AsyncIterator[FastAPI]:
    """Приложение с `lifespan` (схема базы) и фиксированными часами."""
    app = create_app(settings_for_tests(database_url(directory)), replacing((Clock, FixedClock())))
    async with app.router.lifespan_context(app):
        yield app


def browser(app: FastAPI) -> AsyncClient:
    """Клиент со своими cookie, по HTTPS: cookie сеанса — `Secure`."""
    return AsyncClient(transport=ASGITransport(app=app), base_url="https://clinic.test")


async def register_phone(
    client: AsyncClient, name: str = "Анна", time_zone: str = "UTC"
) -> tuple[str, dict[str, str]]:
    """Регистрация с телефона: идентификатор пациента и заголовок с токеном."""
    body = (
        await client.post("/api/v1/register", json={"name": name, "time_zone": time_zone})
    ).json()
    return body["patient_id"], {"Authorization": f"Bearer {body['token']}"}


async def add_doctor(directory: Path, login: str, *patient_ids: str) -> None:
    """Врач через команду администратора и назначение его лечащим врачом пациентов."""
    engine = create_async_engine(database_url(directory))
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    password, hasher = Password(DOCTOR_PASSWORD), Argon2PasswordHasher()
    await create_clinician(sessions, Login(login), ClinicianName(f"Врач {login}"), password, hasher)
    for patient_id in patient_ids:
        await assign_patient(sessions, PatientId(UUID(patient_id)), Login(login))
    await engine.dispose()


async def sign_in(client: AsyncClient, login: str, password: str = DOCTOR_PASSWORD) -> int:
    response = await client.post(
        "/api/v1/auth/sign-in", json={"login": login, "password": password}
    )
    return response.status_code


def admission(**overrides: object) -> dict[str, object]:
    """Карточка нового пациента — вымышленные данные."""
    card: dict[str, object] = {
        "last_name": "Тестов",
        "first_name": "Пётр",
        "middle_name": "Иванович",
        "birth_date": "1961-04-12",
        "height_cm": 176,
        "weight_kg": 82.5,
        "diagnosis": "вымышленный диагноз",
        "time_zone": "Europe/Moscow",
    }
    return card | overrides
