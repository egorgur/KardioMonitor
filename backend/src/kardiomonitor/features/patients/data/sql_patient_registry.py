"""Реестр пациентов в базе."""

from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.patients.data.tables import PatientRow
from kardiomonitor.features.patients.domain.patient import Patient, PatientId, PatientName
from kardiomonitor.features.patients.domain.upload_token import UploadToken


class SqlPatientRegistry:
    """Реализация `PatientRegistry`. Транзакцию фиксирует сценарий, а не реестр (ADR-0005)."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def add(self, patient: Patient, token: UploadToken) -> None:
        self._session.add(
            PatientRow(
                id=patient.id,
                name=patient.name.value,
                time_zone=patient.time_zone.key,
                registered_at=patient.registered_at,
                token_fingerprint=token.fingerprint,
            )
        )
        await self._session.flush()

    async def by_token(self, token: UploadToken) -> Patient | None:
        row = await self._session.scalar(
            select(PatientRow).where(PatientRow.token_fingerprint == token.fingerprint)
        )
        return None if row is None else _to_patient(row)

    async def by_id(self, patient_id: PatientId) -> Patient | None:
        row = await self._session.get(PatientRow, patient_id)
        return None if row is None else _to_patient(row)

    async def all_patients(self) -> list[Patient]:
        rows = await self._session.scalars(select(PatientRow).order_by(PatientRow.registered_at))
        return [_to_patient(row) for row in rows]


def _to_patient(row: PatientRow) -> Patient:
    return Patient(
        id=PatientId(row.id),
        name=PatientName(row.name),
        time_zone=ZoneInfo(row.time_zone),
        registered_at=row.registered_at,
    )
