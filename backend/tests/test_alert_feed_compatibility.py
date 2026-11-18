"""Старая и новая версии телефона: реальный SQL-фильтр перед пагинацией ленты."""

from datetime import date, timedelta
from pathlib import Path
from uuid import UUID

import pytest
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker

from kardiomonitor.features.alerts.data.sql_alert_journal import SqlAlertJournal
from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    DailyTargetResult,
    PatientAlert,
    TrainingMinutesExceeded,
)
from kardiomonitor.features.patients.domain.patient import PatientId
from tests.clinic import browser, register_phone, running_app
from tests.fakes import NOW
from tests.test_training_minutes_end_to_end import training_feed


@pytest.mark.parametrize("count", [99, 100, 101])
async def test_старый_телефон_не_теряет_записи_среди_новых_видов(
    tmp_path: Path,
    count: int,
) -> None:
    async with running_app(tmp_path) as app, browser(app) as phone:
        patient_id, token = await register_phone(phone)
        engine = await app.state.dishka_container.get(AsyncEngine)
        async with async_sessionmaker(engine)() as session:
            journal = SqlAlertJournal(session)
            for i in range(count):
                day = date(2026, 1, 1) + timedelta(days=i)
                await journal.save(
                    PatientAlert.daily(
                        PatientId(UUID(patient_id)),
                        AlertKind.TRAINING_MINUTES_EXCEEDED,
                        day,
                        TrainingMinutesExceeded(61, 60),
                        now=NOW,
                    )
                )
                await journal.save(
                    PatientAlert.daily(
                        PatientId(UUID(patient_id)),
                        AlertKind.DAILY_TARGET_MET,
                        day,
                        DailyTargetResult(61, 30, 12000, 10000),
                        now=NOW,
                    )
                )
            await session.commit()
        first = (await phone.get("/api/v1/me/alerts", headers=token)).json()
        assert len(first["alerts"]) == min(count, 100)
        assert first["cursor"] == 2 * min(count, 100)
        assert first["has_more"] is (count > 100)
        assert {a["kind"] for a in first["alerts"]} == {"daily_target_met"}
        second = (
            await phone.get("/api/v1/me/alerts", headers=token, params={"after": first["cursor"]})
        ).json()
        assert len(second["alerts"]) == max(0, count - 100)
        assert second["has_more"] is False
        modern = await training_feed(phone, token)
        assert len(modern["alerts"]) == 100
        assert modern["has_more"] is True
        assert {a["kind"] for a in modern["alerts"]} == {
            "daily_target_met",
            "training_minutes_exceeded",
        }
