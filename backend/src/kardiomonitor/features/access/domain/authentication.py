"""Вход врача: логин и пароль с учётом блокировки, затем новый сеанс (ТР-11, п. 5)."""

from dataclasses import dataclass

from kardiomonitor.features.access.domain.clinician import Clinician, Clinicians, Login
from kardiomonitor.features.access.domain.clinician_session import (
    ClinicianSession,
    ClinicianSessions,
    SessionToken,
)
from kardiomonitor.features.access.domain.password import PasswordHasher
from kardiomonitor.shared_kernel import Clock, DomainRuleViolation


class InvalidCredentialsError(Exception):
    """Неверный логин или пароль — клиенту одинаково, чтобы не выдать, есть ли логин."""


class SignInLockedError(Exception):
    """Вход врача временно закрыт после неудачных попыток."""


@dataclass(frozen=True, slots=True)
class SignedIn:
    clinician: Clinician
    token: SessionToken


class Authentication:
    """Доменная служба входа: агрегат врача, хеширование, сеансы и часы."""

    def __init__(
        self,
        clinicians: Clinicians,
        hasher: PasswordHasher,
        sessions: ClinicianSessions,
        clock: Clock,
    ) -> None:
        self._clinicians = clinicians
        self._hasher = hasher
        self._sessions = sessions
        self._clock = clock

    async def start_session(self, clinician: Clinician) -> SignedIn:
        """Новый сеанс врача. Вызывать после фиксации `authenticate`: сеанс пишется своей
        транзакцией (ADR-0011), а SQLite не даёт писать, пока транзакция запроса держит запись."""
        token = SessionToken.issue()
        await self._sessions.start(token, ClinicianSession.started(clinician.id, self._clock.now()))
        return SignedIn(clinician, token)

    async def authenticate(self, login: str, password: str) -> Clinician:
        """Врач по логину и паролю. Неудача и успех меняют счётчик в репозитории врачей;
        фиксирует его сценарий — и при отказе тоже."""
        clinician = await self._find(login)
        now = self._clock.now()
        if clinician is not None and clinician.is_locked(now):
            raise SignInLockedError
        hash_ = None if clinician is None else clinician.password_hash
        if not await self._hasher.verify(hash_, password) or clinician is None:
            if clinician is not None:
                await self._clinicians.save(clinician.with_failed_sign_in(now))
            raise InvalidCredentialsError
        signed_in = clinician.with_successful_sign_in()
        await self._clinicians.save(signed_in)
        return signed_in

    async def _find(self, login: str) -> Clinician | None:
        try:
            return await self._clinicians.by_login(Login(login.strip().lower()))
        except DomainRuleViolation:
            return None
