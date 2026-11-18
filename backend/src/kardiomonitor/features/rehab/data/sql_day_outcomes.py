"""Кеш итогов дня в базе."""

from collections.abc import Collection
from datetime import date
from typing import Any

from sqlalchemy import select
from sqlalchemy.dialects.sqlite import insert
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.features.rehab.data.tables import RehabDayOutcomeRow
from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from kardiomonitor.features.rehab.domain.daily_targets import DailyTargets, TrainingHeartRateZone

_OUTCOME_COLUMNS = (
    "zone_lower_bpm",
    "zone_upper_bpm",
    "target_training_minutes",
    "target_steps",
    "training_minutes",
    "steps",
    "walking_minutes",
    "walking_minutes_without_heart_rate",
    "has_data",
)


class SqlDayOutcomes:
    """Реализация `DayOutcomes`. Транзакцию фиксирует сценарий (ADR-0005)."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def of_patients(
        self, patient_ids: Collection[PatientId]
    ) -> dict[PatientId, dict[date, DailyTargetProgress]]:
        rows = await self._session.scalars(
            select(RehabDayOutcomeRow).where(RehabDayOutcomeRow.patient_id.in_(patient_ids))
        )
        found: dict[PatientId, dict[date, DailyTargetProgress]] = {id_: {} for id_ in patient_ids}
        for row in rows:
            found[PatientId(row.patient_id)][row.day] = _to_progress(row)
        return found

    async def save(self, patient_id: PatientId, outcomes: dict[date, DailyTargetProgress]) -> None:
        if not outcomes:
            return
        statement = insert(RehabDayOutcomeRow).values(
            [_to_values(patient_id, day, progress) for day, progress in outcomes.items()]
        )
        # Дней в записи — не больше длительности программы (365): параметров SQLite хватает.
        await self._session.execute(
            statement.on_conflict_do_update(
                index_elements=[RehabDayOutcomeRow.patient_id, RehabDayOutcomeRow.day],
                set_={column: statement.excluded[column] for column in _OUTCOME_COLUMNS},
            )
        )


def _to_values(patient_id: PatientId, day: date, progress: DailyTargetProgress) -> dict[str, Any]:
    targets = progress.targets
    return {
        "patient_id": patient_id,
        "day": day,
        "zone_lower_bpm": targets.training_zone.lower_bpm,
        "zone_upper_bpm": targets.training_zone.upper_bpm,
        "target_training_minutes": targets.training_minutes,
        "target_steps": targets.steps,
        "training_minutes": progress.training_minutes,
        "steps": progress.steps,
        "walking_minutes": progress.walking_minutes,
        "walking_minutes_without_heart_rate": progress.walking_minutes_without_heart_rate,
        "has_data": progress.has_data,
    }


def _to_progress(row: RehabDayOutcomeRow) -> DailyTargetProgress:
    zone = TrainingHeartRateZone(row.zone_lower_bpm, row.zone_upper_bpm)
    return DailyTargetProgress(
        targets=DailyTargets(zone, row.target_training_minutes, row.target_steps),
        training_minutes=row.training_minutes,
        steps=row.steps,
        walking_minutes=row.walking_minutes,
        walking_minutes_without_heart_rate=row.walking_minutes_without_heart_rate,
        has_data=row.has_data,
        is_final=False,
    )
