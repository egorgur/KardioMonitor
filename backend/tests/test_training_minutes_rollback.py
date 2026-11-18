"""Ошибка реакции откатывает минуты, кеш и новое оповещение вместе."""

from pathlib import Path

from dishka import Provider, Scope
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker

from kardiomonitor.app.main import create_app
from kardiomonitor.features.alerts.data.sql_alert_journal import SqlAlertJournal
from kardiomonitor.features.alerts.data.tables import PatientAlertRow
from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.alerts.domain.patient_alert import PatientAlert
from kardiomonitor.features.monitoring.data.tables import ActivityMinuteRow
from kardiomonitor.features.rehab.data.tables import RehabDayOutcomeRow
from kardiomonitor.shared_kernel import Clock
from tests.clinic import add_doctor, browser, register_phone, sign_in
from tests.database import database_url
from tests.fakes import FixedClock, replacing, settings_for_tests
from tests.test_training_minutes_end_to_end import batch, set_threshold


class FailingJournal(SqlAlertJournal):
    async def save(self, alert: PatientAlert) -> None:
        await super().save(alert)
        raise RuntimeError("тестовая ошибка после записи оповещения")


async def test_ошибка_после_записи_превышения_откатывает_весь_upload(tmp_path: Path) -> None:
    failure = Provider(scope=Scope.REQUEST)
    failure.provide(FailingJournal, provides=AlertJournal, override=True)
    app = create_app(
        settings_for_tests(database_url(tmp_path)), replacing((Clock, FixedClock())), failure
    )
    async with app.router.lifespan_context(app), browser(app) as doctor:
        transport = ASGITransport(app=app, raise_app_exceptions=False)
        async with AsyncClient(transport=transport, base_url="https://clinic.test") as phone:
            patient_id, token = await register_phone(phone)
            await add_doctor(tmp_path, "doctor.a", patient_id)
            await sign_in(doctor, "doctor.a")
            await set_threshold(doctor, patient_id, 1)
            response = await phone.post(
                "/api/v1/me/activity-minutes", headers=token, json=batch("2026-10-16T08:00:00Z", 2)
            )
            assert response.status_code == 500
            await _assert_empty(app)


async def test_ошибка_реакции_на_план_откатывает_план_и_новый_снимок(tmp_path: Path) -> None:
    failure = Provider(scope=Scope.REQUEST)
    failure.provide(FailingJournal, provides=AlertJournal, override=True)
    app = create_app(
        settings_for_tests(database_url(tmp_path)), replacing((Clock, FixedClock())), failure
    )
    transport = ASGITransport(app=app, raise_app_exceptions=False)
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=transport, base_url="https://clinic.test") as doctor,
        browser(app) as phone,
    ):
        patient_id, token = await register_phone(phone)
        await add_doctor(tmp_path, "doctor.a", patient_id)
        await sign_in(doctor, "doctor.a")
        await phone.post(
            "/api/v1/me/activity-minutes", headers=token, json=batch("2026-10-16T08:00:00Z", 2)
        )
        url = f"/api/v1/patients/{patient_id}/rehab-plan"
        original = (await doctor.get(url)).json()
        changed = {
            "program": original["program"],
            "monitoring_settings": dict(original["monitoring_settings"]),
        }
        changed["monitoring_settings"]["training_minutes_upper_threshold"] = 1
        response = await doctor.put(url, json=changed)
        assert response.status_code == 500
        assert (await doctor.get(url)).json() == original
        assert (await doctor.get(f"/api/v1/patients/{patient_id}/alerts")).json() == []
        day = (await doctor.get(f"/api/v1/patients/{patient_id}/days/2026-10-16")).json()
        assert day["summary"]["recorded_minutes"] == 2


async def _assert_empty(app: FastAPI) -> None:
    engine = await app.state.dishka_container.get(AsyncEngine)
    async with async_sessionmaker(engine)() as session:
        for table in (ActivityMinuteRow, PatientAlertRow, RehabDayOutcomeRow):
            assert await session.scalar(select(func.count()).select_from(table)) == 0
