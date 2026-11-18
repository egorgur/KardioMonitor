"""Подписчик Мониторинга: эпизоды высокого пульса в принятых данных — оповещения."""

from collections.abc import Sequence
from datetime import datetime

from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.alerts.domain.high_heart_rate_alert import (
    JOIN_WINDOW,
    alert_for_episode,
)
from kardiomonitor.features.alerts.domain.high_heart_rate_episode import (
    HeartRatePoint,
    high_heart_rate_episodes,
)
from kardiomonitor.features.monitoring.domain.monitoring_events import (
    LiveHeartRateReceived,
    MinutesReceived,
)
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.rehab.domain.monitoring_settings import MIN_HIGH_HEART_RATE_THRESHOLD
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans, plan_of
from kardiomonitor.shared_kernel import Clock


class HighHeartRateWatch:
    """Пульс выше порога пациента — оповещение на эпизод; повтор тех же данных — ничего.

    Работает в транзакции приёма, фиксирует сценарий приёма (ADR-0009). Порог — версии плана,
    действовавшей в день замера (ADR-0010).
    """

    def __init__(self, plans: RehabPlans, journal: AlertJournal, clock: Clock) -> None:
        self._plans = plans
        self._journal = journal
        self._clock = clock

    async def minutes_received(self, event: MinutesReceived) -> None:
        points = [
            HeartRatePoint(minute.started_at, minute.heart_rate)
            for minute in event.minutes
            if minute.heart_rate is not None
        ]
        await self._check(event.patient, points)

    async def live_heart_rate_received(self, event: LiveHeartRateReceived) -> None:
        reading = event.reading
        await self._check(event.patient, [HeartRatePoint(reading.measured_at, reading.heart_rate)])

    async def _check(self, patient: Patient, points: Sequence[HeartRatePoint]) -> None:
        # Пульс не выше наименьшего допустимого порога высоким не бывает: план не читаем.
        candidates = [p for p in points if p.heart_rate.bpm > MIN_HIGH_HEART_RATE_THRESHOLD]
        if not candidates:
            return
        plan = await plan_of(patient, self._plans)

        def threshold_at(moment: datetime) -> int:
            return plan.revision_on(patient.day_of(moment)).settings.high_heart_rate_threshold

        for episode in high_heart_rate_episodes(candidates, threshold_at):
            recorded = await self._journal.high_heart_rate_between(
                patient.id, episode.started_at - JOIN_WINDOW, episode.last_at + JOIN_WINDOW
            )
            alert = alert_for_episode(patient.id, episode, recorded, now=self._clock.now())
            if alert is not None:
                await self._journal.save(alert)
