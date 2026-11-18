"""Врач — корень агрегата контекста «Доступ»."""

import re
import uuid
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta
from typing import NewType, Protocol, Self
from uuid import UUID

from kardiomonitor.shared_kernel import DomainRuleViolation

ClinicianId = NewType("ClinicianId", UUID)
"""Идентификатор врача. Выдаётся сервером."""

MAX_FAILED_SIGN_INS = 5
"""Неудачных попыток входа подряд до временной блокировки (ТР-11, п. 5)."""
SIGN_IN_LOCKOUT = timedelta(minutes=15)
MAX_NAME_LENGTH = 100
_LOGIN = re.compile(r"[a-z0-9._-]{3,32}")


@dataclass(frozen=True, slots=True)
class Login:
    """Логин врача: 3–32 знака, строчные латинские буквы, цифры, `.`, `_`, `-`."""

    value: str

    def __post_init__(self) -> None:
        if not _LOGIN.fullmatch(self.value):
            raise DomainRuleViolation(
                "логин — 3–32 знака: строчные латинские буквы, цифры, точка, _ и -",
                code="invalid_login",
            )


@dataclass(frozen=True, slots=True)
class ClinicianName:
    """Как показывать врача в шапке панели: 1–100 знаков, без пробелов по краям."""

    value: str

    def __post_init__(self) -> None:
        if not self.value or self.value != self.value.strip() or len(self.value) > MAX_NAME_LENGTH:
            raise DomainRuleViolation(
                f"имя врача — 1–{MAX_NAME_LENGTH} знаков без пробелов по краям",
                code="invalid_clinician_name",
            )


@dataclass(frozen=True, slots=True)
class Clinician:
    """Врач с аккаунтом на панели. Видит только своих пациентов (ТР-11).

    `password_hash` — Argon2id, сам пароль не хранится; `repr` хеш не раскрывает.
    """

    id: ClinicianId
    login: Login
    name: ClinicianName
    password_hash: str = field(repr=False)
    failed_sign_ins: int = 0
    locked_until: datetime | None = None

    @classmethod
    def register(cls, login: Login, name: ClinicianName, password_hash: str) -> Self:
        return cls(ClinicianId(uuid.uuid4()), login, name, password_hash)

    def is_locked(self, now: datetime) -> bool:
        return self.locked_until is not None and now < self.locked_until

    def with_failed_sign_in(self, now: datetime) -> Self:
        """Ещё одна неудача; пятая подряд закрывает вход на 15 минут и обнуляет счётчик."""
        failures = self.failed_sign_ins + 1
        if failures >= MAX_FAILED_SIGN_INS:
            return replace(self, failed_sign_ins=0, locked_until=now + SIGN_IN_LOCKOUT)
        return replace(self, failed_sign_ins=failures)

    def with_successful_sign_in(self) -> Self:
        return replace(self, failed_sign_ins=0, locked_until=None)

    def with_password_hash(self, password_hash: str) -> Self:
        return replace(self, password_hash=password_hash, failed_sign_ins=0, locked_until=None)


class Clinicians(Protocol):
    """Аккаунты врачей."""

    async def add(self, clinician: Clinician) -> None: ...

    async def save(self, clinician: Clinician) -> None:
        """Сохранить изменения врача: счётчик неудач, блокировку, хеш пароля."""
        ...

    async def by_id(self, clinician_id: ClinicianId) -> Clinician | None: ...

    async def by_login(self, login: Login) -> Clinician | None: ...
