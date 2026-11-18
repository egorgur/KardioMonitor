"""Фейки портов контекста «Реабилитация»."""

from collections.abc import Collection
from dataclasses import replace
from datetime import date

from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan


class FakeRehabPlans:
    """Планы в словаре по пациенту; сохранение плана сбрасывает итоги дней в `outcomes`."""

    def __init__(self, outcomes: "FakeDayOutcomes | None" = None) -> None:
        self.plans: dict[PatientId, RehabPlan] = {}
        self._outcomes = outcomes

    async def of(self, patient_id: PatientId) -> RehabPlan | None:
        return self.plans.get(patient_id)

    async def of_patients(self, patient_ids: Collection[PatientId]) -> dict[PatientId, RehabPlan]:
        return {id_: plan for id_, plan in self.plans.items() if id_ in patient_ids}

    async def save(self, plan: RehabPlan) -> None:
        self.plans[plan.patient_id] = plan
        if self._outcomes is not None:
            self._outcomes.forget(plan.patient_id)


class FakeDayOutcomes:
    """Кеш итогов дня в словаре (пациент, день) → итог."""

    def __init__(self) -> None:
        self.outcomes: dict[tuple[PatientId, date], DailyTargetProgress] = {}

    async def of_patients(
        self, patient_ids: Collection[PatientId]
    ) -> dict[PatientId, dict[date, DailyTargetProgress]]:
        found: dict[PatientId, dict[date, DailyTargetProgress]] = {id_: {} for id_ in patient_ids}
        for (owner, day), progress in self.outcomes.items():
            if owner in found:
                found[owner][day] = replace(progress, is_final=False)
        return found

    async def save(self, patient_id: PatientId, outcomes: dict[date, DailyTargetProgress]) -> None:
        for day, progress in outcomes.items():
            self.outcomes[patient_id, day] = progress

    def forget(self, patient_id: PatientId) -> None:
        self.outcomes = {key: value for key, value in self.outcomes.items() if key[0] != patient_id}
