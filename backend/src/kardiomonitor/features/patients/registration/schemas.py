"""Контракт API слайса «Регистрация»."""

from typing import Annotated, Self
from uuid import UUID

from pydantic import Field

from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.platform.web.api_model import ApiModel

from .register_patient import PatientRegistration


class RegistrationIn(ApiModel):
    """Имя пациента и часовой пояс телефона."""

    name: Annotated[str, Field(min_length=1, max_length=100, examples=["Иван П."])]
    time_zone: Annotated[str, Field(examples=["Asia/Novosibirsk"])]


class RegistrationOut(ApiModel):
    """Токен показывается один раз: телефон сохраняет его и шлёт в `Authorization: Bearer`."""

    patient_id: UUID
    name: str
    time_zone: str
    token: str

    @classmethod
    def from_domain(cls, registration: PatientRegistration) -> Self:
        patient = registration.patient
        return cls(
            patient_id=patient.id,
            name=patient.name.value,
            time_zone=patient.time_zone.key,
            token=registration.token.value,
        )


class PatientProfileOut(ApiModel):
    """Кем сервер считает владельца токена."""

    patient_id: UUID
    name: str
    time_zone: str

    @classmethod
    def from_domain(cls, patient: Patient) -> Self:
        return cls(patient_id=patient.id, name=patient.name.value, time_zone=patient.time_zone.key)
