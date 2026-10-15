"""Точка входа слайса «День пациента»."""

from datetime import date
from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.patient_day import PatientDay
from kardiomonitor.features.patients.api import require_patient
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.platform.web.dashboard_access import require_dashboard_access

from .schemas import PatientDayOut

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(require_dashboard_access)],
)


@router.get("/patients/{patient_id}/days/{day}", summary="Минуты и итоги дня пациента")
async def get_patient_day(
    patient_id: UUID,
    day: date,
    registry: FromDishka[PatientRegistry],
    history: FromDishka[ActivityHistory],
) -> PatientDayOut:
    """`day` — календарный день в часовом поясе пациента."""
    patient = await require_patient(registry, patient_id)
    patient_day = PatientDay(day, patient.time_zone)
    minutes = await history.minutes_between(patient.id, patient_day.start, patient_day.end)
    return PatientDayOut.from_domain(day, patient.time_zone.key, minutes)
