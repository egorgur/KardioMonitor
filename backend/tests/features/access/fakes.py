"""Фейки портов контекста «Доступ»."""

from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from kardiomonitor.features.access.domain.access_log import AccessAction
from kardiomonitor.features.access.domain.clinician import Clinician, ClinicianId, Login
from kardiomonitor.features.access.domain.clinician_session import ClinicianSession, SessionToken
from kardiomonitor.features.access.domain.password import Password


class FakePasswordHasher:
    """«Хеш» — пароль с префиксом: быстро и предсказуемо; `None` — всегда неверно."""

    async def hash(self, password: Password) -> str:
        return "hashed:" + password.value

    async def verify(self, password_hash: str | None, candidate: str) -> bool:
        return password_hash == "hashed:" + candidate


class FakeClinicians:
    def __init__(self, *clinicians: Clinician) -> None:
        self.clinicians: dict[ClinicianId, Clinician] = {c.id: c for c in clinicians}

    async def add(self, clinician: Clinician) -> None:
        self.clinicians[clinician.id] = clinician

    async def save(self, clinician: Clinician) -> None:
        self.clinicians[clinician.id] = clinician

    async def by_id(self, clinician_id: ClinicianId) -> Clinician | None:
        return self.clinicians.get(clinician_id)

    async def by_login(self, login: Login) -> Clinician | None:
        return next((c for c in self.clinicians.values() if c.login == login), None)


class FakeClinicianSessions:
    def __init__(self) -> None:
        self.sessions: dict[str, ClinicianSession] = {}

    async def start(self, token: SessionToken, session: ClinicianSession) -> None:
        self.sessions[token.fingerprint] = session

    async def by_token(self, token: SessionToken) -> ClinicianSession | None:
        return self.sessions.get(token.fingerprint)

    async def touch(self, token: SessionToken, at: datetime) -> None:
        session = self.sessions[token.fingerprint]
        self.sessions[token.fingerprint] = ClinicianSession(
            session.clinician_id, session.started_at, at
        )

    async def end(self, token: SessionToken) -> None:
        self.sessions.pop(token.fingerprint, None)


@dataclass(frozen=True)
class RecordedAccess:
    clinician_id: ClinicianId
    action: AccessAction
    subject_id: UUID


class FakeAccessLog:
    def __init__(self) -> None:
        self.records: list[RecordedAccess] = []

    async def record(
        self, clinician_id: ClinicianId, action: AccessAction, subject_id: UUID
    ) -> None:
        self.records.append(RecordedAccess(clinician_id, action, subject_id))
