"""Точка входа слайса «Карточка пациента»."""

from datetime import date
from typing import Self
from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.access.domain.access_log import AccessAction
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.features.patients.domain.patient_profile import PatientProfile
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime
from kardiomonitor.shared_kernel import Clock, Transaction

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)


class ProfileOut(ApiModel):
    last_name: str
    first_name: str
    middle_name: str | None
    birth_date: date
    age_years: int
    height_cm: int
    weight_kg: float
    diagnosis: str

    @classmethod
    def from_domain(cls, profile: PatientProfile, today: date) -> Self:
        name, body = profile.full_name, profile.body
        return cls(
            last_name=name.last_name,
            first_name=name.first_name,
            middle_name=name.middle_name,
            birth_date=profile.birth_date,
            age_years=profile.age_on(today),
            height_cm=body.height_cm,
            weight_kg=body.weight_kg,
            diagnosis=body.diagnosis,
        )


class PatientCardOut(ApiModel):
    """`profile` null — пациент зарегистрировался с телефона по имени (демо)."""

    patient_id: UUID
    name: str
    time_zone: str
    registered_at: UtcDatetime
    profile: ProfileOut | None


@router.get("/patients/{patient_id}/profile", summary="Карточка пациента")
async def get_patient_card(
    patient_id: UUID,
    patients: FromDishka[AttendedPatients],
    registry: FromDishka[PatientRegistry],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> PatientCardOut:
    """Только лечащему врачу; открытие записывается в журнал доступа (ТР-11, п. 6)."""
    patient = await patients.require(patient_id, recording=AccessAction.VIEWED_PATIENT_CARD)
    await transaction.commit()
    profile = await registry.profile_of(patient.id)
    today = patient.day_of(clock.now())
    return PatientCardOut(
        patient_id=patient.id,
        name=patient.name.value,
        time_zone=patient.time_zone.key,
        registered_at=patient.registered_at,
        profile=None if profile is None else ProfileOut.from_domain(profile, today),
    )
