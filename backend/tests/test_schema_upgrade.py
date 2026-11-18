"""Обновление существующей схемы без удаления пациентов, минут и исторических версий."""

from dataclasses import replace
from datetime import date
from pathlib import Path

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine

from kardiomonitor.app.main import create_app
from kardiomonitor.app.schema_upgrade import require_current_schema, upgrade_schema
from kardiomonitor.features.alerts.data.sql_alert_journal import SqlAlertJournal
from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    LowActivityResult,
    PatientAlert,
)
from kardiomonitor.features.monitoring.data.sql_activity_history import SqlActivityHistory
from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from kardiomonitor.features.patients.data.sql_patient_registry import SqlPatientRegistry
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from kardiomonitor.features.rehab.data.sql_rehab_plans import SqlRehabPlans
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from tests.database import database_session, database_url
from tests.fakes import NOW, settings_for_tests
from tests.features.monitoring.minutes import minute
from tests.features.rehab.plans import PATIENT


async def test_обновление_сохраняет_старый_план_пациента_и_безопасно_повторяется(
    tmp_path: Path,
) -> None:
    default = RehabPlan.default_for(PATIENT)
    plan = default.revised(
        default.current.program,
        replace(default.current.settings, high_heart_rate_threshold=150),
        today=date(2026, 10, 12),
    )
    token = UploadToken.issue()
    batch = MinuteBatch((minute(0, steps=80, heart_rate=100),))
    alert = PatientAlert.daily(
        PATIENT.id, AlertKind.LOW_ACTIVITY, date(2026, 10, 9), LowActivityResult(80, 1000), now=NOW
    )
    async with database_session(tmp_path) as session:
        await SqlPatientRegistry(session).add(PATIENT, token)
        await SqlRehabPlans(session).save(plan)
        await SqlActivityHistory(session).save(PATIENT.id, batch)
        await SqlAlertJournal(session).save(alert)
        await session.commit()
        await session.execute(
            text("ALTER TABLE rehab_plan_revisions DROP COLUMN training_minutes_upper_threshold")
        )
        await session.commit()
    app = create_app(settings_for_tests(database_url(tmp_path)))
    with pytest.raises(RuntimeError, match="schema_upgrade"):
        async with app.router.lifespan_context(app):
            pytest.fail("старая схема не должна запускаться")
    engine = create_async_engine(database_url(tmp_path))
    async with engine.begin() as connection:
        with pytest.raises(RuntimeError, match="schema_upgrade"):
            await connection.run_sync(require_current_schema)
        await connection.run_sync(upgrade_schema)
        await connection.run_sync(upgrade_schema)
        await connection.run_sync(require_current_schema)
    await engine.dispose()
    async with database_session(tmp_path) as session:
        assert await SqlRehabPlans(session).of(PATIENT.id) == plan
        assert await SqlPatientRegistry(session).by_token(token) == PATIENT
        assert await SqlActivityHistory(session).last_minute_of(PATIENT.id) == batch.last_started_at
        assert await SqlAlertJournal(session).latest([PATIENT.id], kind=None, limit=10) == [alert]


async def test_пустая_база_не_требует_обновления(tmp_path: Path) -> None:
    engine = create_async_engine(database_url(tmp_path))
    async with engine.begin() as connection:
        await connection.run_sync(upgrade_schema)
        await connection.run_sync(require_current_schema)
    await engine.dispose()
