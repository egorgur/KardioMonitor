"""Фейк реестра пациентов."""

from kardiomonitor.features.access.domain.clinician import ClinicianId
from kardiomonitor.features.patients.domain.patient import Patient, PatientId
from kardiomonitor.features.patients.domain.patient_profile import PatientProfile
from kardiomonitor.features.patients.domain.upload_token import UploadToken


class FakePatientRegistry:
    """Реестр в словарях: пациенты по id, по отпечатку токена, карточки."""

    def __init__(self) -> None:
        self.patients: dict[PatientId, Patient] = {}
        self.by_fingerprint: dict[str, PatientId] = {}
        self.profiles: dict[PatientId, PatientProfile] = {}

    async def add(self, patient: Patient, token: UploadToken) -> None:
        self.patients[patient.id] = patient
        self.by_fingerprint[token.fingerprint] = patient.id

    async def admit(self, patient: Patient, profile: PatientProfile) -> None:
        self.patients[patient.id] = patient
        self.profiles[patient.id] = profile

    async def by_token(self, token: UploadToken) -> Patient | None:
        patient_id = self.by_fingerprint.get(token.fingerprint)
        return None if patient_id is None else self.patients[patient_id]

    async def by_id(self, patient_id: PatientId) -> Patient | None:
        return self.patients.get(patient_id)

    async def all_patients(self) -> list[Patient]:
        return sorted(self.patients.values(), key=lambda patient: patient.registered_at)

    async def patients_of(self, clinician_id: ClinicianId) -> list[Patient]:
        return [p for p in await self.all_patients() if p.is_attended_by(clinician_id)]

    async def save_attending_clinician(self, patient: Patient) -> None:
        self.patients[patient.id] = patient

    async def profile_of(self, patient_id: PatientId) -> PatientProfile | None:
        return self.profiles.get(patient_id)

    async def replace_token(self, patient_id: PatientId, token: UploadToken) -> None:
        self.by_fingerprint = {f: p for f, p in self.by_fingerprint.items() if p != patient_id}
        self.by_fingerprint[token.fingerprint] = patient_id
