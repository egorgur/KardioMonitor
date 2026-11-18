"""Точка входа слайса «Выполнение нормы дня»."""

from datetime import date
from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.patient_day import PatientDay
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans, plan_of
from kardiomonitor.shared_kernel import Clock

from .schemas import DayTargetProgressOut

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)


@router.get(
    "/patients/{patient_id}/days/{day}/target-progress", summary="Выполнение нормы дня пациента"
)
async def get_day_target_progress(
    patient_id: UUID,
    day: date,
    patients: FromDishka[AttendedPatients],
    history: FromDishka[ActivityHistory],
    plans: FromDishka[RehabPlans],
    clock: FromDishka[Clock],
) -> DayTargetProgressOut:
    """`day` — день в часовом поясе пациента; нормы — версии плана, действовавшей в этот день."""
    patient = await patients.require(patient_id)
    plan = await plan_of(patient, plans)
    program_day = plan.current.program.day_number(day)
    if program_day is None:
        return DayTargetProgressOut.from_domain(day, None, None)
    patient_day = PatientDay(day, patient.time_zone)
    minutes = await history.minutes_between(patient.id, patient_day.start, patient_day.end)
    progress = plan.progress_on(day, minutes, is_final=clock.now() >= patient_day.end)
    return DayTargetProgressOut.from_domain(day, program_day, progress)
