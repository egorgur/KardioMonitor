"""Планы реабилитации на настоящей SQLite: версии и этапы сохраняются без потерь."""

from collections.abc import AsyncIterator
from datetime import time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.patients.data.sql_patient_registry import SqlPatientRegistry
from kardiomonitor.features.patients.domain.patient import Patient, PatientName
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from kardiomonitor.features.rehab.data.sql_rehab_plans import SqlRehabPlans
from kardiomonitor.features.rehab.domain.monitoring_settings import MonitoringSettings
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from tests.database import database_session
from tests.fakes import NOW
from tests.features.rehab.plans import START, program


@pytest.fixture
async def session(tmp_path: Path) -> AsyncIterator[AsyncSession]:
    async with database_session(tmp_path) as session:
        yield session


@pytest.fixture
async def patient(session: AsyncSession) -> Patient:
    patient = Patient.register(PatientName("Пациент"), ZoneInfo("Europe/Moscow"), now=NOW)
    await SqlPatientRegistry(session).add(patient, UploadToken.issue())
    return patient


async def test_несохранённого_плана_нет(session: AsyncSession, patient: Patient) -> None:
    assert await SqlRehabPlans(session).of(patient.id) is None


async def test_версии_и_этапы_читаются_такими_же_как_сохранены(
    session: AsyncSession, patient: Patient
) -> None:
    settings = MonitoringSettings(45, 125, 800, time(16, 30))
    plan = RehabPlan.default_for(patient).revised(program(), settings, START + timedelta(days=20))

    await SqlRehabPlans(session).save(plan)
    await SqlRehabPlans(session).save(plan)  # повтор не дублирует версии

    assert len(plan.revisions) == 2
    assert await SqlRehabPlans(session).of(patient.id) == plan


async def test_планы_многих_пациентов_одним_запросом_без_несохранённых(
    session: AsyncSession, patient: Patient
) -> None:
    other = Patient.register(PatientName("Другой"), ZoneInfo("Europe/Moscow"), now=NOW)
    await SqlPatientRegistry(session).add(other, UploadToken.issue())
    settings = MonitoringSettings(45, 125, 800, time(16, 30))
    plan = RehabPlan.default_for(patient).revised(program(), settings, START + timedelta(days=20))
    await SqlRehabPlans(session).save(plan)

    plans = await SqlRehabPlans(session).of_patients([patient.id, other.id])

    assert plans == {patient.id: plan}
