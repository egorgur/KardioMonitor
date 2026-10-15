"""Фейк реестра пациентов."""

from kardiomonitor.features.patients.domain.patient import Patient, PatientId
from kardiomonitor.features.patients.domain.upload_token import UploadToken


class FakePatientRegistry:
    """Реестр в словарях: пациенты по id и по отпечатку токена."""

    def __init__(self) -> None:
        self.patients: dict[PatientId, Patient] = {}
        self.by_fingerprint: dict[str, PatientId] = {}

    async def add(self, patient: Patient, token: UploadToken) -> None:
        self.patients[patient.id] = patient
        self.by_fingerprint[token.fingerprint] = patient.id

    async def by_token(self, token: UploadToken) -> Patient | None:
        patient_id = self.by_fingerprint.get(token.fingerprint)
        return None if patient_id is None else self.patients[patient_id]

    async def by_id(self, patient_id: PatientId) -> Patient | None:
        return self.patients.get(patient_id)

    async def all_patients(self) -> list[Patient]:
        return sorted(self.patients.values(), key=lambda patient: patient.registered_at)
