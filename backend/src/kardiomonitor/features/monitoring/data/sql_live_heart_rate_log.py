"""Журнал «пульса сейчас» в базе."""

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.dialects.sqlite import insert
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.monitoring.data.tables import LiveHeartRateRow
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.features.monitoring.domain.live_heart_rate import LiveHeartRateReading
from kardiomonitor.features.patients.domain.patient import PatientId


class SqlLiveHeartRateLog:
    """Реализация `LiveHeartRateLog`. Транзакцию фиксирует сценарий (ADR-0005)."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def record(self, patient_id: PatientId, reading: LiveHeartRateReading) -> None:
        statement = insert(LiveHeartRateRow).values(
            patient_id=patient_id,
            measured_at=reading.measured_at,
            heart_rate=reading.heart_rate.bpm,
        )
        await self._session.execute(
            statement.on_conflict_do_update(
                index_elements=[LiveHeartRateRow.patient_id, LiveHeartRateRow.measured_at],
                set_={"heart_rate": statement.excluded.heart_rate},
            )
        )

    async def readings_since(
        self, patient_id: PatientId, since: datetime
    ) -> list[LiveHeartRateReading]:
        rows = await self._session.scalars(
            select(LiveHeartRateRow)
            .where(LiveHeartRateRow.patient_id == patient_id, LiveHeartRateRow.measured_at >= since)
            .order_by(LiveHeartRateRow.measured_at)
        )
        return [_to_reading(row) for row in rows]

    async def latest_of(self, patient_id: PatientId) -> LiveHeartRateReading | None:
        row = await self._session.scalar(
            select(LiveHeartRateRow)
            .where(LiveHeartRateRow.patient_id == patient_id)
            .order_by(LiveHeartRateRow.measured_at.desc())
            .limit(1)
        )
        return None if row is None else _to_reading(row)


def _to_reading(row: LiveHeartRateRow) -> LiveHeartRateReading:
    return LiveHeartRateReading(measured_at=row.measured_at, heart_rate=HeartRate(row.heart_rate))
