"""Слайс «Приём минут»: через HTTP, порты подменены фейками."""

from collections.abc import AsyncIterator
from typing import Any
from zoneinfo import ZoneInfo

import pytest
from httpx import ASGITransport, AsyncClient

from kardiomonitor.app.main import create_app
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.patients.api import CurrentPatient
from kardiomonitor.features.patients.domain.patient import Patient, PatientName
from kardiomonitor.shared_kernel import Transaction
from tests.fakes import NOW, FakeTransaction, replacing, settings_for_tests
from tests.features.monitoring.fakes import FakeActivityHistory

PATIENT = Patient.register(PatientName("Иван П."), ZoneInfo("Asia/Novosibirsk"), now=NOW)
URL = "/api/v1/me/activity-minutes"


@pytest.fixture
def history() -> FakeActivityHistory:
    return FakeActivityHistory()


@pytest.fixture
def transaction() -> FakeTransaction:
    return FakeTransaction()


@pytest.fixture
async def client(
    history: FakeActivityHistory, transaction: FakeTransaction
) -> AsyncIterator[AsyncClient]:
    app = create_app(
        settings_for_tests(),
        replacing(
            (ActivityHistory, history),
            (Transaction, transaction),
            (CurrentPatient, CurrentPatient(PATIENT)),
        ),
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client


def minute_json(hour: int, minute: int, *, heart_rate: int | None = 72) -> dict[str, Any]:
    return {
        "started_at": f"2026-10-15T{hour:02}:{minute:02}:00+07:00",
        "steps": 12,
        "heart_rate": heart_rate,
        "kind": "other",
        "kind_code": 80,
        "intensity": 30,
    }


async def test_принятые_минуты_сохраняются_и_фиксируются_до_ответа(
    client: AsyncClient, history: FakeActivityHistory, transaction: FakeTransaction
) -> None:
    body = {"minutes": [minute_json(10, 0), minute_json(10, 1, heart_rate=None)]}

    response = await client.post(URL, json=body)

    assert response.status_code == 200
    assert response.json() == {
        "received": 2,
        "new": 2,
        "last_minute_started_at": "2026-10-15T03:01:00Z",
    }
    assert len(history.minutes_of(PATIENT.id)) == 2
    assert transaction.commits == 1


async def test_повторная_загрузка_тех_же_минут_не_дублирует_историю(
    client: AsyncClient, history: FakeActivityHistory
) -> None:
    body = {"minutes": [minute_json(10, m) for m in range(30)]}

    await client.post(URL, json=body)
    response = await client.post(URL, json=body)

    assert response.json()["new"] == 0
    assert len(history.minutes_of(PATIENT.id)) == 30


async def test_пакет_с_нарушением_правила_не_сохраняется_ни_частично(
    client: AsyncClient, history: FakeActivityHistory, transaction: FakeTransaction
) -> None:
    body = {"minutes": [minute_json(10, 0), minute_json(10, 0)]}

    response = await client.post(URL, json=body)

    assert response.status_code == 422
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["code"] == "duplicate_minute"
    assert history.minutes == {}
    assert transaction.commits == 0


async def test_пустой_пакет_отклоняется_с_кодом_правила_а_не_общей_ошибкой_формата(
    client: AsyncClient,
) -> None:
    response = await client.post(URL, json={"minutes": []})

    assert response.status_code == 422
    assert response.json()["code"] == "empty_minute_batch"


async def test_время_минуты_без_часового_пояса_отклоняется(client: AsyncClient) -> None:
    naive = minute_json(10, 0) | {"started_at": "2026-10-15T10:00:00"}

    response = await client.post(URL, json={"minutes": [naive]})

    assert response.status_code == 422
    assert response.json()["code"] == "invalid_request"


async def test_неизвестное_поле_в_минуте_отклоняется_а_не_теряется(client: AsyncClient) -> None:
    typo = minute_json(10, 0) | {"heartrate": 72}

    response = await client.post(URL, json={"minutes": [typo]})

    assert response.status_code == 422
