"""Точка входа слайса «Прогресс реабилитации»."""

from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans, plan_of
from kardiomonitor.features.rehab.domain.rehab_progress import RehabProgress
from kardiomonitor.shared_kernel import Clock

from .schemas import RehabProgressOut

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)


@router.get("/patients/{patient_id}/rehab-progress", summary="Прогресс реабилитации пациента")
async def get_rehab_progress(
    patient_id: UUID,
    patients: FromDishka[AttendedPatients],
    history: FromDishka[ActivityHistory],
    plans: FromDishka[RehabPlans],
    clock: FromDishka[Clock],
) -> RehabProgressOut:
    """Считается по минутам всех прошедших дней программы (requirements-design.md, «Риски»)."""
    patient = await patients.require(patient_id)
    plan = await plan_of(patient, plans)
    today = patient.day_of(clock.now())
    start, end = RehabProgress.period(plan, time_zone=patient.time_zone, today=today)
    minutes = await history.minutes_between(patient.id, start, end)
    progress = RehabProgress.of(plan, minutes, time_zone=patient.time_zone, today=today)
    return RehabProgressOut.from_domain(progress)
