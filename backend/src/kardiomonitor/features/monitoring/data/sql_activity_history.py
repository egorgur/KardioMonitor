"""История активности в базе (SQLite, ADR-0007)."""

from collections.abc import Sequence
from datetime import datetime
from itertools import batched
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.dialects.sqlite import insert
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.monitoring.data.tables import ActivityMinuteRow
from kardiomonitor.features.monitoring.domain.activity_history import SaveOutcome
from kardiomonitor.features.monitoring.domain.activity_minute import (
    ActivityKind,
    ActivityMinute,
    Intensity,
    StepCount,
)
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from kardiomonitor.features.patients.domain.patient import PatientId

# SQLite ограничивает число параметров запроса (32 766): 7 колонок × 1000 строк — с запасом.
_ROWS_PER_STATEMENT = 1000


class SqlActivityHistory:
    """Реализация `ActivityHistory`. Транзакцию фиксирует сценарий (ADR-0005)."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def save(self, patient_id: PatientId, batch: MinuteBatch) -> SaveOutcome:
        new_minutes = 0
        for chunk in batched(batch.minutes, _ROWS_PER_STATEMENT, strict=False):
            new_minutes += len(chunk) - await self._count_existing(patient_id, chunk)
            await self._upsert(patient_id, chunk)
        return SaveOutcome(new_minutes)

    async def last_minute_of(self, patient_id: PatientId) -> datetime | None:
        return await self._session.scalar(
            select(func.max(ActivityMinuteRow.started_at)).where(
                ActivityMinuteRow.patient_id == patient_id
            )
        )

    async def minutes_between(
        self, patient_id: PatientId, start: datetime, end: datetime
    ) -> list[ActivityMinute]:
        rows = await self._session.scalars(
            select(ActivityMinuteRow)
            .where(
                ActivityMinuteRow.patient_id == patient_id,
                ActivityMinuteRow.started_at >= start,
                ActivityMinuteRow.started_at < end,
            )
            .order_by(ActivityMinuteRow.started_at)
        )
        return [_to_minute(row) for row in rows]

    async def _count_existing(self, patient_id: PatientId, chunk: Sequence[ActivityMinute]) -> int:
        count = await self._session.scalar(
            select(func.count()).where(
                ActivityMinuteRow.patient_id == patient_id,
                ActivityMinuteRow.started_at.in_([minute.started_at for minute in chunk]),
            )
        )
        return count or 0

    async def _upsert(self, patient_id: PatientId, chunk: Sequence[ActivityMinute]) -> None:
        statement = insert(ActivityMinuteRow).values([_to_values(patient_id, m) for m in chunk])
        excluded = statement.excluded
        await self._session.execute(
            statement.on_conflict_do_update(
                index_elements=[ActivityMinuteRow.patient_id, ActivityMinuteRow.started_at],
                set_={
                    "steps": excluded.steps,
                    "heart_rate": excluded.heart_rate,
                    "kind": excluded.kind,
                    "kind_code": excluded.kind_code,
                    "intensity": excluded.intensity,
                },
            )
        )


def _to_values(patient_id: PatientId, minute: ActivityMinute) -> dict[str, Any]:
    return {
        "patient_id": patient_id,
        "started_at": minute.started_at,
        "steps": minute.steps.value,
        "heart_rate": None if minute.heart_rate is None else minute.heart_rate.bpm,
        "kind": minute.kind.value,
        "kind_code": minute.kind_code,
        "intensity": minute.intensity.value,
    }


def _to_minute(row: ActivityMinuteRow) -> ActivityMinute:
    return ActivityMinute(
        started_at=row.started_at,
        steps=StepCount(row.steps),
        heart_rate=None if row.heart_rate is None else HeartRate(row.heart_rate),
        kind=ActivityKind(row.kind),
        kind_code=row.kind_code,
        intensity=Intensity(row.intensity),
    )
