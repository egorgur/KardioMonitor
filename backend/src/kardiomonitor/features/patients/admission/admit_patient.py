"""Сценарии: врач создаёт пациента; выдаёт новый код привязки телефона."""

from dataclasses import dataclass
from datetime import datetime
from zoneinfo import ZoneInfo

from kardiomonitor.features.access.domain.access_log import AccessAction
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.features.patients.domain.enrollment_code import (
    ENROLLMENT_CODE_LIFETIME,
    EnrollmentCode,
    EnrollmentCodes,
)
from kardiomonitor.features.patients.domain.patient import Patient, PatientId
from kardiomonitor.features.patients.domain.patient_profile import PatientProfile
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.shared_kernel import Clock, Transaction


@dataclass(frozen=True, slots=True)
class Admission:
    """Что ввёл врач: карточка и часовой пояс пациента."""

    profile: PatientProfile
    time_zone: ZoneInfo


@dataclass(frozen=True, slots=True)
class IssuedCode:
    """Код показывается один раз: в базе — только отпечаток."""

    code: EnrollmentCode
    expires_at: datetime


async def admit_patient(
    admission: Admission,
    *,
    patients: AttendedPatients,
    registry: PatientRegistry,
    codes: EnrollmentCodes,
    clock: Clock,
    transaction: Transaction,
) -> tuple[Patient, IssuedCode]:
    """Новый пациент вошедшего врача с карточкой и кодом привязки; программа — по умолчанию
    (ТР-01, п. 5). Создание записывается в журнал доступа."""
    now = clock.now()
    clinician_id = patients.clinician.id
    patient = Patient.admit(admission.profile, admission.time_zone, clinician_id, now=now)
    await registry.admit(patient, admission.profile)
    await patients.record(AccessAction.CREATED_PATIENT, patient.id)
    issued = await _issue(patient.id, codes, now)
    await transaction.commit()
    return patient, issued


async def reissue_code(
    patient_id: PatientId, *, codes: EnrollmentCodes, clock: Clock, transaction: Transaction
) -> IssuedCode:
    """Новый код привязки (QR потерян, новый телефон); прежний перестаёт действовать."""
    issued = await _issue(patient_id, codes, clock.now())
    await transaction.commit()
    return issued


async def _issue(patient_id: PatientId, codes: EnrollmentCodes, now: datetime) -> IssuedCode:
    issued = IssuedCode(EnrollmentCode.issue(), now + ENROLLMENT_CODE_LIFETIME)
    await codes.issue(patient_id, issued.code, issued.expires_at)
    return issued
