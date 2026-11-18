"""Порт реакции верхних контекстов на сохранённую версию плана (ADR-0009)."""

from datetime import datetime
from typing import Protocol

from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan


class PlanChanged(Protocol):
    async def plan_changed(self, patient: Patient, plan: RehabPlan, *, now: datetime) -> None:
        """Проверить сегодняшний итог в транзакции изменения плана, до commit."""
        ...
