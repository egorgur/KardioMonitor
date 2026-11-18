"""Провайдер dishka контекста «Доступ»: реализации портов и текущий врач (ADR-0003, ADR-0011)."""

from dishka import Provider, Scope, provide
from fastapi import Request

from kardiomonitor.features.access.api import SESSION_COOKIE, CurrentClinician, NotSignedInError
from kardiomonitor.features.access.data.argon2_password_hasher import Argon2PasswordHasher
from kardiomonitor.features.access.data.sql_access_log import SqlAccessLog
from kardiomonitor.features.access.data.sql_clinician_sessions import SqlClinicianSessions
from kardiomonitor.features.access.data.sql_clinicians import SqlClinicians
from kardiomonitor.features.access.domain.access_log import AccessLog
from kardiomonitor.features.access.domain.authentication import Authentication
from kardiomonitor.features.access.domain.clinician import Clinicians
from kardiomonitor.features.access.domain.clinician_session import ClinicianSessions, SessionToken
from kardiomonitor.features.access.domain.password import PasswordHasher
from kardiomonitor.shared_kernel import Clock


class AccessProvider(Provider):
    """Реализации портов контекста — только здесь знают о `data/`."""

    scope = Scope.REQUEST

    hasher = provide(Argon2PasswordHasher, provides=PasswordHasher, scope=Scope.APP)
    sessions = provide(SqlClinicianSessions, provides=ClinicianSessions, scope=Scope.APP)
    clinicians = provide(SqlClinicians, provides=Clinicians)
    access_log = provide(SqlAccessLog, provides=AccessLog)
    authentication = provide(Authentication)

    @provide
    async def current_clinician(
        self,
        request: Request,
        sessions: ClinicianSessions,
        clinicians: Clinicians,
        clock: Clock,
    ) -> CurrentClinician:
        value = request.cookies.get(SESSION_COOKIE)
        token = SessionToken(value) if value else None
        session = None if token is None else await sessions.by_token(token)
        now = clock.now()
        if token is None or session is None or session.is_expired(now):
            raise NotSignedInError
        if session.needs_touch(now):
            await sessions.touch(token, now)
        clinician = await clinicians.by_id(session.clinician_id)
        if clinician is None:
            raise NotSignedInError
        return CurrentClinician(clinician)
