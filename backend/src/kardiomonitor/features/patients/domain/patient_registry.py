"""Порт: реестр пациентов."""

from typing import Protocol

from kardiomonitor.features.access.domain.clinician import ClinicianId
from kardiomonitor.features.patients.domain.patient import Patient, PatientId
from kardiomonitor.features.patients.domain.patient_profile import PatientProfile
from kardiomonitor.features.patients.domain.upload_token import UploadToken


class PatientRegistry(Protocol):
    """Все зарегистрированные пациенты и токены их телефонов."""

    async def add(self, patient: Patient, token: UploadToken) -> None:
        """Сохранить нового пациента с токеном его телефона."""
        ...

    async def by_token(self, token: UploadToken) -> Patient | None:
        """Пациент, которому выдан токен; None — токен неизвестен."""
        ...

    async def by_id(self, patient_id: PatientId) -> Patient | None: ...

    async def all_patients(self) -> list[Patient]:
        """Все пациенты в порядке регистрации."""
        ...

    async def patients_of(self, clinician_id: ClinicianId) -> list[Patient]:
        """Пациенты лечащего врача в порядке регистрации."""
        ...

    async def save_attending_clinician(self, patient: Patient) -> None:
        """Сохранить лечащего врача пациента."""
        ...

    async def admit(self, patient: Patient, profile: PatientProfile) -> None:
        """Сохранить пациента, созданного клиникой, с карточкой и лечащим врачом. Токена у него
        ещё нет: телефон получит его при привязке (`replace_token`)."""
        ...

    async def profile_of(self, patient_id: PatientId) -> PatientProfile | None:
        """Карточка; None — пациент зарегистрировался с телефона по имени."""
        ...

    async def replace_token(self, patient_id: PatientId, token: UploadToken) -> None:
        """Новый токен загрузки пациента; прежний перестаёт действовать."""
        ...
