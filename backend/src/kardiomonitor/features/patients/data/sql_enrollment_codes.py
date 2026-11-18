"""Коды привязки телефона в базе."""

from datetime import datetime

from sqlalchemy import delete, update
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.patients.data.tables import EnrollmentCodeRow
from kardiomonitor.features.patients.domain.enrollment_code import EnrollmentCode
from kardiomonitor.features.patients.domain.patient import PatientId


class SqlEnrollmentCodes:
    """Реализация `EnrollmentCodes`. Транзакцию фиксирует сценарий (ADR-0005)."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def issue(
        self, patient_id: PatientId, code: EnrollmentCode, expires_at: datetime
    ) -> None:
        # Непогашенные коды ничего не значат для истории — удаляются; погашенные остаются.
        await self._session.execute(
            delete(EnrollmentCodeRow).where(
                EnrollmentCodeRow.patient_id == patient_id,
                EnrollmentCodeRow.redeemed_at.is_(None),
            )
        )
        self._session.add(
            EnrollmentCodeRow(
                fingerprint=code.fingerprint, patient_id=patient_id, expires_at=expires_at
            )
        )
        await self._session.flush()

    async def redeem(self, code: EnrollmentCode, now: datetime) -> PatientId | None:
        patient_id = await self._session.scalar(
            update(EnrollmentCodeRow)
            .where(
                EnrollmentCodeRow.fingerprint == code.fingerprint,
                EnrollmentCodeRow.redeemed_at.is_(None),
                EnrollmentCodeRow.expires_at > now,
            )
            .values(redeemed_at=now)
            .returning(EnrollmentCodeRow.patient_id)
        )
        return None if patient_id is None else PatientId(patient_id)
