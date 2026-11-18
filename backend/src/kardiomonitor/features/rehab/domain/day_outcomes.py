"""Кеш итогов дня: выполнение нормы, посчитанное по минутам дня (ТР-06, п. 4).

Список пациентов показывает прогресс всех пациентов врача сразу; считать его по всем минутам
программы каждого (42 дня ≈ 60 000 минут) для сотен пациентов слишком долго. Итоги хранятся
по дням и пересчитываются, когда приходят минуты дня (подписчик Мониторинга, ADR-0009);
сохранение плана (`RehabPlans.save`) сбрасывает итоги пациента. Источник истины — минуты: кеш
можно удалить в любой момент.
"""

from collections import defaultdict
from collections.abc import Collection, Mapping
from dataclasses import replace
from datetime import date
from typing import Protocol

from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.monitoring.domain.patient_day import PatientDay
from kardiomonitor.features.patients.domain.patient import Patient, PatientId
from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan


class DayOutcomes(Protocol):
    """Итоги дней пациентов. Записанный итог — по всем минутам дня, принятым к записи."""

    async def of_patients(
        self, patient_ids: Collection[PatientId]
    ) -> dict[PatientId, dict[date, DailyTargetProgress]]:
        """Все записанные итоги пациентов одним запросом (список на главной — сотни пациентов).

        Закончились ли сутки, кеш не знает: `is_final` — False, уточняет `as_of`.
        """
        ...

    async def save(self, patient_id: PatientId, outcomes: dict[date, DailyTargetProgress]) -> None:
        """Записать итоги дней, заменив прежние."""
        ...


def as_of(
    outcomes: Mapping[date, DailyTargetProgress], *, today: date
) -> dict[date, DailyTargetProgress]:
    """Итоги из кеша с `is_final` на сегодня пациента: сутки до сегодняшнего закончились."""
    return {day: replace(progress, is_final=day < today) for day, progress in outcomes.items()}


async def refresh_day_outcomes(
    patient: Patient,
    plan: RehabPlan,
    days: Collection[date],
    *,
    today: date,
    history: ActivityHistory,
    outcomes: DayOutcomes,
) -> dict[date, DailyTargetProgress]:
    """Пересчитать итоги дней по минутам и записать; дни вне программы пропускаются."""
    if not days:
        return {}
    time_zone = patient.time_zone
    start, end = PatientDay(min(days), time_zone).start, PatientDay(max(days), time_zone).end
    by_day: defaultdict[date, list[ActivityMinute]] = defaultdict(list)
    for minute in await history.minutes_between(patient.id, start, end):
        by_day[patient.day_of(minute.started_at)].append(minute)
    refreshed = {
        day: progress
        for day in days
        if (progress := plan.progress_on(day, by_day[day], is_final=day < today)) is not None
    }
    await outcomes.save(patient.id, refreshed)
    return refreshed
