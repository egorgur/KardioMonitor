"""Сеанс врача на панели: токен в cookie, истекает без запросов (ТР-11, ADR-0011)."""

import hashlib
import secrets
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Protocol, Self

from kardiomonitor.features.access.domain.clinician import ClinicianId

SESSION_IDLE_TIMEOUT = timedelta(hours=8)
"""Сеанс завершается после 8 ч без запросов — решено 2026-10-29 (ТР-11, п. 5; В-23)."""
SESSION_TOUCH_INTERVAL = timedelta(minutes=5)
"""Последний запрос записывается не чаще: панель опрашивает сервер каждые несколько секунд."""


@dataclass(frozen=True, slots=True)
class SessionToken:
    """Секрет сеанса в cookie. В базе — только отпечаток; `repr` значение не раскрывает."""

    value: str = field(repr=False)

    @classmethod
    def issue(cls) -> Self:
        """Новый случайный токен (256 бит)."""
        return cls(secrets.token_urlsafe(32))

    @property
    def fingerprint(self) -> str:
        return hashlib.sha256(self.value.encode()).hexdigest()


@dataclass(frozen=True, slots=True)
class ClinicianSession:
    clinician_id: ClinicianId
    started_at: datetime
    last_seen_at: datetime

    @classmethod
    def started(cls, clinician_id: ClinicianId, now: datetime) -> Self:
        return cls(clinician_id, now, now)

    def is_expired(self, now: datetime) -> bool:
        return now - self.last_seen_at >= SESSION_IDLE_TIMEOUT

    def needs_touch(self, now: datetime) -> bool:
        return now - self.last_seen_at >= SESSION_TOUCH_INTERVAL


class ClinicianSessions(Protocol):
    """Сеансы врачей. Пишутся сразу, своей транзакцией (ADR-0011): продление сеанса не должно
    зависеть от того, фиксирует ли сценарий запроса что-нибудь."""

    async def start(self, token: SessionToken, session: ClinicianSession) -> None: ...

    async def by_token(self, token: SessionToken) -> ClinicianSession | None: ...

    async def touch(self, token: SessionToken, at: datetime) -> None: ...

    async def end(self, token: SessionToken) -> None:
        """Завершить сеанс; неизвестный токен — ничего не делать."""
        ...
