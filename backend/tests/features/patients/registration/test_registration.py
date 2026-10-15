"""Слайс «Регистрация»: через HTTP, реестр подменён фейком."""

from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

from kardiomonitor.app.main import create_app
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.shared_kernel import Clock, Transaction
from tests.fakes import FakeTransaction, FixedClock, replacing, settings_for_tests
from tests.features.patients.fakes import FakePatientRegistry


@pytest.fixture
def registry() -> FakePatientRegistry:
    return FakePatientRegistry()


@pytest.fixture
async def client(registry: FakePatientRegistry) -> AsyncIterator[AsyncClient]:
    app = create_app(
        settings_for_tests(),
        replacing(
            (PatientRegistry, registry),
            (Transaction, FakeTransaction()),
            (Clock, FixedClock()),
        ),
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client


async def register(client: AsyncClient, name: str = "Иван П.") -> str:
    response = await client.post(
        "/api/v1/register", json={"name": name, "time_zone": "Asia/Novosibirsk"}
    )
    assert response.status_code == 201
    token: str = response.json()["token"]
    return token


async def test_по_токену_из_регистрации_сервер_узнаёт_пациента(client: AsyncClient) -> None:
    token = await register(client)

    response = await client.get("/api/v1/me", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200
    assert response.json()["name"] == "Иван П."
    assert response.json()["time_zone"] == "Asia/Novosibirsk"


async def test_токен_хранится_только_отпечатком(
    client: AsyncClient, registry: FakePatientRegistry
) -> None:
    token = await register(client)

    assert all(token not in fingerprint for fingerprint in registry.by_fingerprint)


async def test_без_токена_или_с_чужим_токеном_ответ_401(client: AsyncClient) -> None:
    await register(client)

    without = await client.get("/api/v1/me")
    unknown = await client.get("/api/v1/me", headers={"Authorization": "Bearer wrong-token"})

    assert without.status_code == unknown.status_code == 401
    assert without.json()["code"] == "unauthorized"


async def test_неизвестный_часовой_пояс_при_регистрации_отклоняется(client: AsyncClient) -> None:
    response = await client.post("/api/v1/register", json={"name": "Иван", "time_zone": "Mars/X"})

    assert response.status_code == 422
    assert response.json()["code"] == "unknown_time_zone"
