"""Сценарий: прогресс пациентов для списка — по кешу итогов дня."""

from collections.abc import Sequence
from datetime import datetime

from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.rehab.domain.day_outcomes import (
    DayOutcomes,
    as_of,
    refresh_day_outcomes,
)
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan, RehabPlans
from kardiomonitor.features.rehab.domain.rehab_progress import RehabProgress
from kardiomonitor.shared_kernel import Transaction


async def overview_progress(
    patients: Sequence[Patient],
    *,
    now: datetime,
    plans: RehabPlans,
    outcomes: DayOutcomes,
    history: ActivityHistory,
    transaction: Transaction,
) -> list[RehabProgress]:
    """Прогресс каждого пациента, в том же порядке (ТР-06, п. 4: сотни пациентов).

    Планы и итоги дней — одним запросом на всех; минуты читаются только за дни без итога (после
    правки плана, дни без минут), их итоги записываются и фиксируются до ответа (ADR-0005).
    """
    patient_ids = [patient.id for patient in patients]
    stored_plans = await plans.of_patients(patient_ids)
    cached = await outcomes.of_patients(patient_ids)
    progress = []
    for patient in patients:
        plan = stored_plans.get(patient.id) or RehabPlan.default_for(patient)
        today = patient.day_of(now)
        known = as_of(cached[patient.id], today=today)
        missing = [day for day in RehabProgress.elapsed_days(plan, today=today) if day not in known]
        refreshed = await refresh_day_outcomes(
            patient, plan, missing, today=today, history=history, outcomes=outcomes
        )
        progress.append(RehabProgress.from_outcomes(plan, known | refreshed, today=today))
    await transaction.commit()
    return progress
