"""Контракт API слайса «Создание пациента» (api-contract.md). Правила — в домене."""

from datetime import date
from typing import Self
from uuid import UUID

from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.patients.domain.patient_profile import (
    BodyParameters,
    FullName,
    PatientProfile,
)
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime

from .admit_patient import IssuedCode


class PatientAdmissionIn(ApiModel):
    """Карточка пациента. `repr` не раскрывает данные: тело запроса не попадает в журналы."""

    last_name: str
    first_name: str
    middle_name: str | None = None
    birth_date: date
    height_cm: int
    weight_kg: float
    diagnosis: str
    time_zone: str

    def __repr__(self) -> str:
        return "PatientAdmissionIn(***)"

    def to_profile(self) -> PatientProfile:
        middle = self.middle_name or None  # пустая строка из формы — «отчества нет»
        return PatientProfile(
            FullName(self.last_name, self.first_name, middle),
            self.birth_date,
            BodyParameters(self.height_cm, self.weight_kg, self.diagnosis),
        )


class EnrollmentCodeOut(ApiModel):
    """Код для QR v2 (`e=`): одноразовый, показывается один раз."""

    enrollment_code: str
    enrollment_code_expires_at: UtcDatetime

    @classmethod
    def from_domain(cls, issued: IssuedCode) -> Self:
        return cls(enrollment_code=issued.code.value, enrollment_code_expires_at=issued.expires_at)


class AdmittedPatientOut(EnrollmentCodeOut):
    patient_id: UUID
    name: str

    @classmethod
    def of(cls, patient: Patient, issued: IssuedCode) -> Self:
        return cls(
            patient_id=patient.id,
            name=patient.name.value,
            enrollment_code=issued.code.value,
            enrollment_code_expires_at=issued.expires_at,
        )
