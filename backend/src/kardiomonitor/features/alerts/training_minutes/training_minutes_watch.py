"""Реакции на минуты и изменение плана: один неизменяемый снимок превышения за день."""

from collections.abc import Collection
from datetime import date, datetime

from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.alerts.domain.patient_alert import AlertKind
from kardiomonitor.features.alerts.domain.training_minutes_alert import training_minutes_alert
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.monitoring_events import (
    LiveHeartRateReceived,
    MinutesReceived,
)
from kardiomonitor.features.monitoring.domain.patient_day import PatientDay
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan, RehabPlans, plan_of
from kardiomonitor.shared_kernel import Clock


class TrainingMinutesWatch:
    """Не фиксирует транзакцию: снимок сохраняется вместе с минутами или планом."""

    def __init__(
        self,
        plans: RehabPlans,
        history: ActivityHistory,
        journal: AlertJournal,
        clock: Clock,
    ) -> None:
        self._plans = plans
        self._history = history
        self._journal = journal
        self._clock = clock

    async def minutes_received(self, event: MinutesReceived) -> None:
        await self._check_days(
            event.patient,
            await plan_of(event.patient, self._plans),
            {event.patient.day_of(minute.started_at) for minute in event.minutes},
            now=self._clock.now(),
        )

    async def plan_changed(self, patient: Patient, plan: RehabPlan, *, now: datetime) -> None:
        await self._check_days(patient, plan, {patient.day_of(now)}, now=now)

    async def live_heart_rate_received(self, event: LiveHeartRateReceived) -> None:
        """«Пульс сейчас» не добавляет тренировочных минут."""

    async def _check_days(
        self,
        patient: Patient,
        plan: RehabPlan,
        days: Collection[date],
        *,
        now: datetime,
    ) -> None:
        for day in sorted(days):
            if await self._journal.has_kind_on(
                patient.id, AlertKind.TRAINING_MINUTES_EXCEEDED, day
            ):
                continue
            revision = plan.revision_on(day)
            if (
                revision.settings.training_minutes_upper_threshold is None
                or revision.program.targets_on(day) is None
            ):
                continue
            period = PatientDay(day, patient.time_zone)
            minutes = await self._history.minutes_between(patient.id, period.start, period.end)
            alert = training_minutes_alert(patient, plan, day, minutes, now=now)
            if alert is not None:
                await self._journal.save(alert)
