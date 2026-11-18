"""Планы реабилитации в базе."""

from collections import defaultdict
from collections.abc import Collection
from typing import Any

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.features.rehab.data.tables import RehabDayOutcomeRow, RehabPlanRevisionRow
from kardiomonitor.features.rehab.domain.daily_targets import DailyTargets, TrainingHeartRateZone
from kardiomonitor.features.rehab.domain.monitoring_settings import MonitoringSettings
from kardiomonitor.features.rehab.domain.rehab_plan import ProgramRevision, RehabPlan
from kardiomonitor.features.rehab.domain.rehab_program import RehabProgram, RehabStage


class SqlRehabPlans:
    """Реализация `RehabPlans`. Транзакцию фиксирует сценарий (ADR-0005)."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def of(self, patient_id: PatientId) -> RehabPlan | None:
        rows = await self._session.scalars(
            select(RehabPlanRevisionRow)
            .where(RehabPlanRevisionRow.patient_id == patient_id)
            .order_by(RehabPlanRevisionRow.effective_from)
        )
        revisions = tuple(_to_revision(row) for row in rows)
        return RehabPlan(patient_id, revisions) if revisions else None

    async def of_patients(self, patient_ids: Collection[PatientId]) -> dict[PatientId, RehabPlan]:
        rows = await self._session.scalars(
            select(RehabPlanRevisionRow)
            .where(RehabPlanRevisionRow.patient_id.in_(patient_ids))
            .order_by(RehabPlanRevisionRow.effective_from)
        )
        revisions: defaultdict[PatientId, list[ProgramRevision]] = defaultdict(list)
        for row in rows:
            revisions[PatientId(row.patient_id)].append(_to_revision(row))
        return {
            patient_id: RehabPlan(patient_id, tuple(found))
            for patient_id, found in revisions.items()
        }

    async def save(self, plan: RehabPlan) -> None:
        """Версий у пациента единицы: проще заменить все, чем искать изменённые.

        Кеш итогов дня пациента сбрасывается здесь, а не в сценарии: его не забудет ни один
        сценарий, сохраняющий план (нормы сегодня и программа могли измениться).
        """
        await self._session.execute(
            delete(RehabPlanRevisionRow).where(RehabPlanRevisionRow.patient_id == plan.patient_id)
        )
        await self._session.execute(
            delete(RehabDayOutcomeRow).where(RehabDayOutcomeRow.patient_id == plan.patient_id)
        )
        self._session.add_all(_to_row(plan.patient_id, revision) for revision in plan.revisions)
        await self._session.flush()


def _to_row(patient_id: PatientId, revision: ProgramRevision) -> RehabPlanRevisionRow:
    program, settings = revision.program, revision.settings
    return RehabPlanRevisionRow(
        patient_id=patient_id,
        effective_from=revision.effective_from,
        program_start_date=program.start_date,
        duration_days=program.duration_days,
        stages=[_stage_json(stage) for stage in program.stages],
        walking_steps_threshold=settings.walking_steps_threshold,
        high_heart_rate_threshold=settings.high_heart_rate_threshold,
        low_activity_steps=settings.low_activity_steps,
        daily_alert_time_utc=settings.daily_alert_time_utc,
        training_minutes_upper_threshold=settings.training_minutes_upper_threshold,
    )


def _to_revision(row: RehabPlanRevisionRow) -> ProgramRevision:
    program = RehabProgram(
        row.program_start_date, row.duration_days, tuple(_stage(item) for item in row.stages)
    )
    settings = MonitoringSettings(
        walking_steps_threshold=row.walking_steps_threshold,
        high_heart_rate_threshold=row.high_heart_rate_threshold,
        low_activity_steps=row.low_activity_steps,
        daily_alert_time_utc=row.daily_alert_time_utc,
        training_minutes_upper_threshold=row.training_minutes_upper_threshold,
    )
    return ProgramRevision(row.effective_from, program, settings)


def _stage_json(stage: RehabStage) -> dict[str, Any]:
    targets = stage.targets
    return {
        "first_day": stage.first_day,
        "last_day": stage.last_day,
        "zone_lower_bpm": targets.training_zone.lower_bpm,
        "zone_upper_bpm": targets.training_zone.upper_bpm,
        "training_minutes": targets.training_minutes,
        "steps": targets.steps,
    }


def _stage(item: dict[str, Any]) -> RehabStage:
    zone = TrainingHeartRateZone(item["zone_lower_bpm"], item["zone_upper_bpm"])
    targets = DailyTargets(zone, item["training_minutes"], item["steps"])
    return RehabStage(item["first_day"], item["last_day"], targets)
