"""История активности на настоящей SQLite: upsert, порции, часовые пояса."""

from collections.abc import AsyncIterator
from datetime import timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.monitoring.data.sql_activity_history import SqlActivityHistory
from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from kardiomonitor.features.patients.data.sql_patient_registry import SqlPatientRegistry
from kardiomonitor.features.patients.domain.patient import Patient, PatientName
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from tests.database import database_session
from tests.fakes import NOW
from tests.features.monitoring.minutes import DAY_START, minute

NOVOSIBIRSK = ZoneInfo("Asia/Novosibirsk")


@pytest.fixture
async def session(tmp_path: Path) -> AsyncIterator[AsyncSession]:
    async with database_session(tmp_path) as session:
        yield session


@pytest.fixture
async def patient(session: AsyncSession) -> Patient:
    patient = Patient.register(PatientName("Иван П."), NOVOSIBIRSK, now=NOW)
    await SqlPatientRegistry(session).add(patient, UploadToken.issue())
    return patient


async def test_повтор_минуты_перезаписывает_её_и_не_считается_новой(
    session: AsyncSession, patient: Patient
) -> None:
    history = SqlActivityHistory(session)

    first = await history.save(patient.id, MinuteBatch((minute(0, steps=5), minute(1))))
    second = await history.save(patient.id, MinuteBatch((minute(1, steps=40), minute(2))))
    stored = await history.minutes_between(patient.id, DAY_START, DAY_START + timedelta(days=1))

    assert (first.new_minutes, second.new_minutes) == (2, 1)
    assert [m.steps.value for m in stored] == [5, 40, 0]


async def test_пакет_за_семь_суток_сохраняется_целиком_несмотря_на_лимит_параметров_sqlite(
    session: AsyncSession, patient: Patient
) -> None:
    history = SqlActivityHistory(session)
    week = MinuteBatch(tuple(minute(index, heart_rate=70) for index in range(7 * 24 * 60)))

    outcome = await history.save(patient.id, week)

    assert outcome.new_minutes == 7 * 24 * 60
    assert await history.last_minute_of(patient.id) == week.last_started_at


async def test_время_минуты_читается_тем_же_моментом_что_записано_в_поясе_телефона(
    session: AsyncSession, patient: Patient
) -> None:
    history = SqlActivityHistory(session)
    phone_minute = minute(0, start=DAY_START.astimezone(NOVOSIBIRSK))

    await history.save(patient.id, MinuteBatch((phone_minute,)))

    assert await history.last_minute_of(patient.id) == phone_minute.started_at


async def test_у_пациента_без_минут_нет_последней_минуты(
    session: AsyncSession, patient: Patient
) -> None:
    assert await SqlActivityHistory(session).last_minute_of(patient.id) is None
