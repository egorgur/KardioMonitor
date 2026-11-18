"""Точка входа слайса «Нормы для телефона»."""

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.patients.api import CurrentPatient, bearer_scheme
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans, plan_of

from .schemas import PhoneRehabPlanOut

router = APIRouter(
    prefix="/api/v1",
    tags=["Телефон"],
    route_class=DishkaRoute,
    dependencies=[Depends(bearer_scheme)],
)


@router.get("/me/rehab-plan", summary="Программа и нормы пациента для телефона")
async def get_my_rehab_plan(
    patient: FromDishka[CurrentPatient], plans: FromDishka[RehabPlans]
) -> PhoneRehabPlanOut:
    """Текущая версия плана; врач его не менял — программа по умолчанию."""
    plan = await plan_of(patient, plans)
    return PhoneRehabPlanOut.from_domain(plan, time_zone=patient.time_zone.key)
