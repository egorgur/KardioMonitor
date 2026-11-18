"""Точка входа слайса «День пациента»."""

from datetime import date
from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.patient_day import PatientDay
from kardiomonitor.features.patients.api import AttendedPatients

from .schemas import PatientDayOut

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)


@router.get("/patients/{patient_id}/days/{day}", summary="Минуты и итоги дня пациента")
async def get_patient_day(
    patient_id: UUID,
    day: date,
    patients: FromDishka[AttendedPatients],
    history: FromDishka[ActivityHistory],
) -> PatientDayOut:
    """`day` — календарный день в часовом поясе пациента."""
    patient = await patients.require(patient_id)
    patient_day = PatientDay(day, patient.time_zone)
    minutes = await history.minutes_between(patient.id, patient_day.start, patient_day.end)
    return PatientDayOut.from_domain(day, patient.time_zone.key, minutes)
