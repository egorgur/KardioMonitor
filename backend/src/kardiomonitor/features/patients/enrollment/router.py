"""Точка входа слайса «Привязка телефона»."""

from typing import Annotated, Self
from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter
from pydantic import Field

from kardiomonitor.features.patients.domain.enrollment_code import EnrollmentCode, EnrollmentCodes
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from kardiomonitor.platform.web.api_model import ApiModel
from kardiomonitor.shared_kernel import Clock, Transaction

from .enroll_phone import enroll_phone

router = APIRouter(prefix="/api/v1", tags=["Телефон"], route_class=DishkaRoute)


class EnrollmentIn(ApiModel):
    code: Annotated[str, Field(min_length=1, max_length=64)]

    def __repr__(self) -> str:
        return "EnrollmentIn(code=***)"


class EnrolledPhoneOut(ApiModel):
    """Как ответ регистрации: токен показывается один раз."""

    patient_id: UUID
    name: str
    time_zone: str
    token: str

    @classmethod
    def from_domain(cls, patient: Patient, token: UploadToken) -> Self:
        return cls(
            patient_id=patient.id,
            name=patient.name.value,
            time_zone=patient.time_zone.key,
            token=token.value,
        )


@router.post("/enroll", status_code=201, summary="Привязать телефон по коду из QR")
async def enroll(
    body: EnrollmentIn,
    registry: FromDishka[PatientRegistry],
    codes: FromDishka[EnrollmentCodes],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> EnrolledPhoneOut:
    """Код одноразовый; неизвестный, использованный и истёкший — 422 `invalid_enrollment_code`."""
    patient, token = await enroll_phone(
        EnrollmentCode(body.code),
        registry=registry,
        codes=codes,
        clock=clock,
        transaction=transaction,
    )
    return EnrolledPhoneOut.from_domain(patient, token)
