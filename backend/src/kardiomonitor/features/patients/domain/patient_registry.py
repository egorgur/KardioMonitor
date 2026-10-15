"""Порт: реестр пациентов."""

from typing import Protocol

from kardiomonitor.features.patients.domain.patient import Patient, PatientId
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
