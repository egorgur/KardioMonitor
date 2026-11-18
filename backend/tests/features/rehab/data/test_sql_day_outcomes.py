"""Кеш итогов дня на настоящей SQLite: итог читается как записан, правка плана его сбрасывает."""

from collections.abc import AsyncIterator
from datetime import date
from pathlib import Path

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.patients.data.sql_patient_registry import SqlPatientRegistry
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from kardiomonitor.features.rehab.data.sql_day_outcomes import SqlDayOutcomes
from kardiomonitor.features.rehab.data.sql_rehab_plans import SqlRehabPlans
from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from kardiomonitor.features.rehab.domain.day_outcomes import as_of
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from tests.database import database_session
from tests.features.rehab.plans import PATIENT, targets

DAY = date(2026, 10, 5)
PROGRESS = DailyTargetProgress(
    targets=targets(40, 8_000, (95, 115)),
    training_minutes=15,
    steps=6_200,
    walking_minutes=41,
    walking_minutes_without_heart_rate=3,
    has_data=True,
    is_final=True,
)


@pytest.fixture
async def session(tmp_path: Path) -> AsyncIterator[AsyncSession]:
    async with database_session(tmp_path) as session:
        await SqlPatientRegistry(session).add(PATIENT, UploadToken.issue())
        yield session


async def test_итог_дня_читается_как_записан(session: AsyncSession) -> None:
    outcomes = SqlDayOutcomes(session)
    await outcomes.save(PATIENT.id, {DAY: PROGRESS})

    stored = (await outcomes.of_patients([PATIENT.id]))[PATIENT.id]

    assert as_of(stored, today=date(2026, 10, 12)) == {DAY: PROGRESS}
    assert as_of(stored, today=DAY)[DAY].is_final is False


async def test_повторная_запись_дня_заменяет_итог(session: AsyncSession) -> None:
    outcomes = SqlDayOutcomes(session)
    await outcomes.save(PATIENT.id, {DAY: PROGRESS})
    newer = DailyTargetProgress(PROGRESS.targets, 30, 9_000, 50, 0, has_data=True, is_final=True)

    await outcomes.save(PATIENT.id, {DAY: newer})

    stored = (await outcomes.of_patients([PATIENT.id]))[PATIENT.id]
    assert as_of(stored, today=date(2026, 10, 12)) == {DAY: newer}


async def test_сохранение_плана_сбрасывает_итоги_пациента(session: AsyncSession) -> None:
    outcomes = SqlDayOutcomes(session)
    await outcomes.save(PATIENT.id, {DAY: PROGRESS})

    await SqlRehabPlans(session).save(RehabPlan.default_for(PATIENT))

    assert await outcomes.of_patients([PATIENT.id]) == {PATIENT.id: {}}
