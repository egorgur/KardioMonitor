"""Код привязки телефона — одноразовый секрет в QR v2 (`e=`, android/docs/qr-contract.md)."""

import hashlib
import secrets
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Protocol, Self

from kardiomonitor.features.patients.domain.patient import PatientId

ENROLLMENT_CODE_LIFETIME = timedelta(hours=72)
"""Сколько действует код — предположение: QR выдают при выписке, телефон привязывают сразу."""


@dataclass(frozen=True, slots=True)
class EnrollmentCode:
    """128 бит, base64url (22 знака): без `;` и без 32 hex-знаков подряд — правило QR-контракта.
    В базе — только отпечаток; `repr` значение не раскрывает."""

    value: str = field(repr=False)

    @classmethod
    def issue(cls) -> Self:
        return cls(secrets.token_urlsafe(16))

    @property
    def fingerprint(self) -> str:
        return hashlib.sha256(self.value.encode()).hexdigest()


class EnrollmentCodes(Protocol):
    """Выданные коды привязки."""

    async def issue(
        self, patient_id: PatientId, code: EnrollmentCode, expires_at: datetime
    ) -> None:
        """Новый код пациента; прежние неиспользованные коды пациента перестают действовать."""
        ...

    async def redeem(self, code: EnrollmentCode, now: datetime) -> PatientId | None:
        """Погасить код: пациент кода или None — код неизвестен, использован или истёк.
        Погашение атомарно: один код — одна привязка."""
        ...
