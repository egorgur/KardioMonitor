"""Фейки портов контекста «Мониторинг»."""

from datetime import datetime

from kardiomonitor.features.monitoring.domain.activity_history import SaveOutcome
from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.monitoring.domain.live_heart_rate import LiveHeartRateReading
from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from kardiomonitor.features.monitoring.domain.monitoring_events import (
    LiveHeartRateReceived,
    MinutesReceived,
)
from kardiomonitor.features.patients.domain.patient import PatientId


class FakeActivityHistory:
    """История в словаре (пациент, минута) → минута: повтор минуты перезаписывает её."""

    def __init__(self) -> None:
        self.minutes: dict[tuple[PatientId, datetime], ActivityMinute] = {}

    async def save(self, patient_id: PatientId, batch: MinuteBatch) -> SaveOutcome:
        new_minutes = sum(
            1 for m in batch.minutes if (patient_id, m.started_at) not in self.minutes
        )
        for minute in batch.minutes:
            self.minutes[patient_id, minute.started_at] = minute
        return SaveOutcome(new_minutes)

    async def last_minute_of(self, patient_id: PatientId) -> datetime | None:
        return max((start for owner, start in self.minutes if owner == patient_id), default=None)

    async def minutes_between(
        self, patient_id: PatientId, start: datetime, end: datetime
    ) -> list[ActivityMinute]:
        return sorted(
            (
                m
                for (owner, at), m in self.minutes.items()
                if owner == patient_id and start <= at < end
            ),
            key=lambda m: m.started_at,
        )

    def minutes_of(self, patient_id: PatientId) -> list[ActivityMinute]:
        return [minute for (owner, _), minute in self.minutes.items() if owner == patient_id]


class FakeLiveHeartRateLog:
    """Журнал «пульса сейчас» в словаре."""

    def __init__(self) -> None:
        self.readings: dict[tuple[PatientId, datetime], LiveHeartRateReading] = {}

    async def record(self, patient_id: PatientId, reading: LiveHeartRateReading) -> None:
        self.readings[patient_id, reading.measured_at] = reading

    async def readings_since(
        self, patient_id: PatientId, since: datetime
    ) -> list[LiveHeartRateReading]:
        return sorted(
            (r for (owner, at), r in self.readings.items() if owner == patient_id and at >= since),
            key=lambda r: r.measured_at,
        )

    async def latest_of(self, patient_id: PatientId) -> LiveHeartRateReading | None:
        own = [r for (owner, _), r in self.readings.items() if owner == patient_id]
        return max(own, key=lambda r: r.measured_at, default=None)


class RecordingSubscriber:
    """Подписчик Мониторинга, который запоминает события."""

    def __init__(self) -> None:
        self.events: list[MinutesReceived | LiveHeartRateReceived] = []

    async def minutes_received(self, event: MinutesReceived) -> None:
        self.events.append(event)

    async def live_heart_rate_received(self, event: LiveHeartRateReceived) -> None:
        self.events.append(event)
