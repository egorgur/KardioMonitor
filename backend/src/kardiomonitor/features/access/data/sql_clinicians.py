"""Врачи в базе."""

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.access.data.tables import ClinicianRow
from kardiomonitor.features.access.domain.clinician import (
    Clinician,
    ClinicianId,
    ClinicianName,
    Login,
)


class SqlClinicians:
    """Реализация `Clinicians`. Транзакцию фиксирует сценарий (ADR-0005)."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def add(self, clinician: Clinician) -> None:
        self._session.add(_to_row(clinician))
        await self._session.flush()

    async def save(self, clinician: Clinician) -> None:
        await self._session.merge(_to_row(clinician))
        await self._session.flush()

    async def by_id(self, clinician_id: ClinicianId) -> Clinician | None:
        row = await self._session.get(ClinicianRow, clinician_id)
        return None if row is None else _to_clinician(row)

    async def by_login(self, login: Login) -> Clinician | None:
        row = await self._session.scalar(
            select(ClinicianRow).where(ClinicianRow.login == login.value)
        )
        return None if row is None else _to_clinician(row)


def _to_row(clinician: Clinician) -> ClinicianRow:
    return ClinicianRow(
        id=clinician.id,
        login=clinician.login.value,
        name=clinician.name.value,
        password_hash=clinician.password_hash,
        failed_sign_ins=clinician.failed_sign_ins,
        locked_until=clinician.locked_until,
    )


def _to_clinician(row: ClinicianRow) -> Clinician:
    return Clinician(
        id=ClinicianId(row.id),
        login=Login(row.login),
        name=ClinicianName(row.name),
        password_hash=row.password_hash,
        failed_sign_ins=row.failed_sign_ins,
        locked_until=row.locked_until,
    )
