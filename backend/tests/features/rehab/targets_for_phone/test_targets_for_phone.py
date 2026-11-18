"""Слайс «Нормы для телефона»: через HTTP, токен телефона подменён (ТР-01, п. 9)."""

from collections.abc import AsyncIterator
from datetime import time

import pytest
from httpx import ASGITransport, AsyncClient

from kardiomonitor.app.main import create_app
from kardiomonitor.features.patients.api import CurrentPatient
from kardiomonitor.features.rehab.domain.monitoring_settings import MonitoringSettings
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan, RehabPlans
from tests.fakes import replacing, settings_for_tests
from tests.features.rehab.fakes import FakeRehabPlans
from tests.features.rehab.plans import PATIENT, START, program

URL = "/api/v1/me/rehab-plan"


@pytest.fixture
async def client(plans: FakeRehabPlans) -> AsyncIterator[AsyncClient]:
    app = create_app(
        settings_for_tests(),
        replacing((RehabPlans, plans), (CurrentPatient, CurrentPatient(PATIENT))),
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client


async def test_телефон_получает_программу_по_умолчанию_и_порог_ходьбы(client: AsyncClient) -> None:
    body = (await client.get(URL)).json()

    assert body["walking_steps_threshold"] == 60
    assert body["program"]["duration_days"] == 42
    assert body["program"]["stages"][0]["targets"]["training_minutes"] == 30
    assert body["time_zone"] == "Europe/Moscow"


async def test_телефон_получает_этапы_и_порог_из_текущей_версии(
    client: AsyncClient, plans: FakeRehabPlans
) -> None:
    settings = MonitoringSettings(45, 130, 1_000, time(17))
    plans.plans[PATIENT.id] = RehabPlan.default_for(PATIENT).revised(program(), settings, START)

    body = (await client.get(URL)).json()

    assert body["walking_steps_threshold"] == 45
    assert [stage["first_day"] for stage in body["program"]["stages"]] == [1, 16]
    assert "high_heart_rate_threshold" not in body
