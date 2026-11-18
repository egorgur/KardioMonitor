"""Фейки портов контекста «Оповещения»."""

from collections.abc import Collection
from dataclasses import replace
from datetime import date, datetime

from kardiomonitor.features.alerts.domain.alert_journal import FeedEntry
from kardiomonitor.features.alerts.domain.patient_alert import AlertId, AlertKind, PatientAlert
from kardiomonitor.features.patients.domain.patient import PatientId


class FakeAlertJournal:
    """Журнал в словаре по id; позиция в ленте растёт с каждой записью."""

    def __init__(self) -> None:
        self.alerts: dict[AlertId, PatientAlert] = {}
        self.positions: dict[AlertId, int] = {}

    async def save(self, alert: PatientAlert) -> None:
        if alert.id in self.alerts and alert.kind is AlertKind.TRAINING_MINUTES_EXCEEDED:
            return
        self.alerts[alert.id] = alert
        self.positions[alert.id] = max(self.positions.values(), default=0) + 1

    async def high_heart_rate_between(
        self, patient_id: PatientId, start: datetime, end: datetime
    ) -> list[PatientAlert]:
        return [
            alert
            for alert in self.alerts.values()
            if alert.patient_id == patient_id
            and (episode := alert.episode) is not None
            and episode.started_at <= end
            and episode.last_at >= start
        ]

    async def has_daily_alerts(self, patient_id: PatientId, day: date) -> bool:
        return any(
            a.patient_id == patient_id and a.day == day and a.kind.is_scheduled
            for a in self.alerts.values()
        )

    async def has_kind_on(self, patient_id: PatientId, kind: AlertKind, day: date) -> bool:
        return any(
            a.patient_id == patient_id and a.kind == kind and a.day == day
            for a in self.alerts.values()
        )

    async def latest(
        self, patient_ids: Collection[PatientId], *, kind: AlertKind | None, limit: int
    ) -> list[PatientAlert]:
        chosen = [
            alert
            for alert in self.alerts.values()
            if alert.patient_id in patient_ids and kind in (None, alert.kind)
        ]
        chosen.sort(key=lambda alert: (alert.created_at, self.positions[alert.id]), reverse=True)
        return chosen[:limit]

    async def mark_viewed(self, patient_id: PatientId, alert_id: AlertId, at: datetime) -> bool:
        alert = self.alerts.get(alert_id)
        if alert is None or alert.patient_id != patient_id:
            return False
        if alert.viewed_at is None:
            self.alerts[alert_id] = replace(alert, viewed_at=at)
        return True

    async def unviewed_counts(self, patient_ids: Collection[PatientId]) -> dict[PatientId, int]:
        return {
            patient_id: sum(
                1 for a in self.alerts.values() if a.patient_id == patient_id and not a.is_viewed
            )
            for patient_id in patient_ids
        }

    async def feed(
        self,
        patient_id: PatientId,
        *,
        after: int,
        limit: int,
        kinds: Collection[AlertKind] | None = None,
    ) -> list[FeedEntry]:
        entries = [
            FeedEntry(self.positions[alert.id], alert)
            for alert in self.alerts.values()
            if alert.patient_id == patient_id
            and self.positions[alert.id] > after
            and (kinds is None or alert.kind in kinds)
        ]
        return sorted(entries, key=lambda entry: entry.position)[:limit]

    def of_kind(self, kind: AlertKind) -> list[PatientAlert]:
        return [alert for alert in self.alerts.values() if alert.kind is kind]
