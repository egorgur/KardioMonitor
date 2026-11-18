"""Подписчик Мониторинга: пересчитать итоги дней, за которые пришли минуты."""

from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.monitoring_events import (
    LiveHeartRateReceived,
    MinutesReceived,
)
from kardiomonitor.features.rehab.domain.day_outcomes import DayOutcomes, refresh_day_outcomes
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans, plan_of
from kardiomonitor.shared_kernel import Clock


class DayOutcomeRefresh:
    """Итог дня пересчитывается по всем его минутам — и сегодняшнего «пока», и прошлых дней при
    поздней синхронизации (ТР-03, п. 2). Работает в транзакции приёма (ADR-0009)."""

    def __init__(
        self,
        plans: RehabPlans,
        history: ActivityHistory,
        outcomes: DayOutcomes,
        clock: Clock,
    ) -> None:
        self._plans = plans
        self._history = history
        self._outcomes = outcomes
        self._clock = clock

    async def minutes_received(self, event: MinutesReceived) -> None:
        patient = event.patient
        days = {patient.day_of(minute.started_at) for minute in event.minutes}
        await refresh_day_outcomes(
            patient,
            await plan_of(patient, self._plans),
            days,
            today=patient.day_of(self._clock.now()),
            history=self._history,
            outcomes=self._outcomes,
        )

    async def live_heart_rate_received(self, event: LiveHeartRateReceived) -> None:
        """«Пульс сейчас» в итоги дня не входит: итоги — по минутам."""
