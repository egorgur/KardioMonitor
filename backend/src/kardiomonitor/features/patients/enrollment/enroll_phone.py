"""Сценарий: телефон обменивает одноразовый код привязки на токен загрузки."""

from kardiomonitor.features.patients.domain.enrollment_code import EnrollmentCode, EnrollmentCodes
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from kardiomonitor.shared_kernel import Clock, DomainRuleViolation, Transaction


async def enroll_phone(
    code: EnrollmentCode,
    *,
    registry: PatientRegistry,
    codes: EnrollmentCodes,
    clock: Clock,
    transaction: Transaction,
) -> tuple[Patient, UploadToken]:
    """Код гасится, пациент получает новый токен; прежний телефон пациента отключается."""
    patient_id = await codes.redeem(code, clock.now())
    patient = None if patient_id is None else await registry.by_id(patient_id)
    if patient is None:
        raise DomainRuleViolation(
            "код привязки недействителен, использован или истёк — попросите в клинике новый QR",
            code="invalid_enrollment_code",
        )
    token = UploadToken.issue()
    await registry.replace_token(patient.id, token)
    await transaction.commit()
    return patient, token
