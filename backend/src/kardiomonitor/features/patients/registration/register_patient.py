"""Сценарий: зарегистрировать пациента и выдать токен его телефону."""

from dataclasses import dataclass, field

from kardiomonitor.features.patients.domain.patient import Patient, PatientName, time_zone_of
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from kardiomonitor.shared_kernel import Clock, Transaction


@dataclass(frozen=True, slots=True)
class PatientRegistration:
    """Итог регистрации: пациент и токен, который телефон получает один раз."""

    patient: Patient
    token: UploadToken = field(repr=False)


async def register_patient(
    name: str,
    time_zone: str,
    *,
    registry: PatientRegistry,
    clock: Clock,
    transaction: Transaction,
) -> PatientRegistration:
    """Новый пациент с часовым поясом телефона и новым токеном загрузки."""
    patient = Patient.register(PatientName(name), time_zone_of(time_zone), now=clock.now())
    token = UploadToken.issue()
    await registry.add(patient, token)
    await transaction.commit()
    return PatientRegistration(patient, token)
