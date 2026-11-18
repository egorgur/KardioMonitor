"""Сценарий: врач меняет программу и параметры наблюдения пациента."""

from dataclasses import replace

from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.rehab.domain.monitoring_settings import MonitoringSettings
from kardiomonitor.features.rehab.domain.plan_changed import PlanChanged
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan, RehabPlans, plan_of
from kardiomonitor.features.rehab.domain.rehab_program import RehabProgram
from kardiomonitor.shared_kernel import Clock, Transaction


class RevisePlan:
    """Сохранение и реакции верхних контекстов — одна транзакция."""

    def __init__(
        self,
        plans: RehabPlans,
        clock: Clock,
        transaction: Transaction,
        reaction: PlanChanged,
    ) -> None:
        self._plans = plans
        self._clock = clock
        self._transaction = transaction
        self._reaction = reaction

    async def __call__(
        self,
        patient: Patient,
        program: RehabProgram,
        settings: MonitoringSettings,
        *,
        preserve_threshold: bool = False,
    ) -> RehabPlan:
        """Новая версия с сегодняшнего дня; старый PUT сохраняет существующий верхний порог."""
        plan = await plan_of(patient, self._plans)
        if preserve_threshold:
            settings = replace(
                settings,
                training_minutes_upper_threshold=plan.current.settings.training_minutes_upper_threshold,
            )
        now = self._clock.now()
        revised = plan.revised(program, settings, today=patient.day_of(now))
        await self._plans.save(revised)
        await self._reaction.plan_changed(patient, revised, now=now)
        await self._transaction.commit()
        return revised
