"""Сеансы врачей в базе — своей короткой транзакцией на каждое действие (ADR-0011)."""

from datetime import datetime

from sqlalchemy import delete, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from kardiomonitor.features.access.data.tables import ClinicianSessionRow
from kardiomonitor.features.access.domain.clinician import ClinicianId
from kardiomonitor.features.access.domain.clinician_session import ClinicianSession, SessionToken


class SqlClinicianSessions:
    """Реализация `ClinicianSessions`: продление сеанса сохраняется и на запросах только для
    чтения, где сценарий ничего не фиксирует."""

    def __init__(self, sessionmaker: async_sessionmaker[AsyncSession]) -> None:
        self._sessionmaker = sessionmaker

    async def start(self, token: SessionToken, session: ClinicianSession) -> None:
        async with self._sessionmaker.begin() as db:
            db.add(
                ClinicianSessionRow(
                    token_fingerprint=token.fingerprint,
                    clinician_id=session.clinician_id,
                    started_at=session.started_at,
                    last_seen_at=session.last_seen_at,
                )
            )

    async def by_token(self, token: SessionToken) -> ClinicianSession | None:
        async with self._sessionmaker() as db:
            row = await db.get(ClinicianSessionRow, token.fingerprint)
            if row is None:
                return None
            return ClinicianSession(ClinicianId(row.clinician_id), row.started_at, row.last_seen_at)

    async def touch(self, token: SessionToken, at: datetime) -> None:
        async with self._sessionmaker.begin() as db:
            await db.execute(
                update(ClinicianSessionRow)
                .where(ClinicianSessionRow.token_fingerprint == token.fingerprint)
                .values(last_seen_at=at)
            )

    async def end(self, token: SessionToken) -> None:
        async with self._sessionmaker.begin() as db:
            await db.execute(
                delete(ClinicianSessionRow).where(
                    ClinicianSessionRow.token_fingerprint == token.fingerprint
                )
            )
