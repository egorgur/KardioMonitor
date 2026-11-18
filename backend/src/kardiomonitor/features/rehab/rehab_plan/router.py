"""Точка входа слайса «План реабилитации»."""

from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.access.domain.access_log import AccessAction
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan, RehabPlans
from kardiomonitor.shared_kernel import Transaction

from .revise_plan import RevisePlan
from .schemas import RehabPlanIn, RehabPlanOut

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)


@router.get("/patients/{patient_id}/rehab-plan", summary="План реабилитации пациента")
async def get_rehab_plan(
    patient_id: UUID,
    patients: FromDishka[AttendedPatients],
    plans: FromDishka[RehabPlans],
    transaction: FromDishka[Transaction],
) -> RehabPlanOut:
    """Текущая версия; план не сохранялся — программа по умолчанию с дня регистрации.

    Открытие плана записывается в журнал доступа (ТР-11, п. 6).
    """
    patient = await patients.require(patient_id, recording=AccessAction.VIEWED_REHAB_PLAN)
    await transaction.commit()
    stored = await plans.of(patient.id)
    plan = stored or RehabPlan.default_for(patient)
    return RehabPlanOut.from_domain(
        plan, time_zone=patient.time_zone.key, is_default=stored is None
    )


@router.put("/patients/{patient_id}/rehab-plan", summary="Изменить план реабилитации пациента")
async def change_rehab_plan(
    patient_id: UUID,
    body: RehabPlanIn,
    patients: FromDishka[AttendedPatients],
    revise_plan: FromDishka[RevisePlan],
) -> RehabPlanOut:
    """Действует с сегодняшнего дня пациента; прошлые дни оцениваются прежними нормами."""
    program, settings = body.program.to_domain(), body.monitoring_settings.to_domain()
    patient = await patients.require(patient_id, recording=AccessAction.CHANGED_REHAB_PLAN)
    plan = await revise_plan(
        patient,
        program,
        settings,
        preserve_threshold="training_minutes_upper_threshold"
        not in body.monitoring_settings.model_fields_set,
    )
    return RehabPlanOut.from_domain(plan, time_zone=patient.time_zone.key, is_default=False)
