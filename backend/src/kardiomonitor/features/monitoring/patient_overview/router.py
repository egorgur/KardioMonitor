"""Точка входа слайса «Пациенты на панели»."""

from typing import Annotated
from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends, Query

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.live_heart_rate import LiveHeartRateLog
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)


class PatientOverviewOut(ApiModel):
    """Пациент и свежесть его данных."""

    patient_id: UUID
    name: str
    time_zone: str
    registered_at: UtcDatetime
    last_minute_started_at: UtcDatetime | None
    live_heart_rate: int | None
    live_heart_rate_measured_at: UtcDatetime | None


@router.get("/patients", summary="Пациенты врача и свежесть их данных")
async def list_patients(
    patients: FromDishka[AttendedPatients],
    history: FromDishka[ActivityHistory],
    live_log: FromDishka[LiveHeartRateLog],
    search: Annotated[str | None, Query(max_length=100, description="Подстрока ФИО")] = None,
) -> list[PatientOverviewOut]:
    """Для демо пациентов единицы: два запроса на пациента допустимы."""
    overview = []
    for patient in await patients.all(search=search):
        live = await live_log.latest_of(patient.id)
        overview.append(
            PatientOverviewOut(
                patient_id=patient.id,
                name=patient.name.value,
                time_zone=patient.time_zone.key,
                registered_at=patient.registered_at,
                last_minute_started_at=await history.last_minute_of(patient.id),
                live_heart_rate=None if live is None else live.heart_rate.bpm,
                live_heart_rate_measured_at=None if live is None else live.measured_at,
            )
        )
    return overview
