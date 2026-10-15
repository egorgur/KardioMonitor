"""Построители минут активности для тестов."""

from datetime import UTC, datetime, timedelta

from kardiomonitor.features.monitoring.domain.activity_minute import (
    ActivityKind,
    ActivityMinute,
    Intensity,
    StepCount,
)
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate

DAY_START = datetime(2026, 10, 9, tzinfo=UTC)


def minute(
    index: int,
    *,
    heart_rate: int | None = None,
    steps: int = 0,
    kind: ActivityKind = ActivityKind.OTHER,
    start: datetime = DAY_START,
) -> ActivityMinute:
    """Минута с номером `index` от `start`; по умолчанию — как в Android `DailySummaryTest`."""
    return ActivityMinute(
        started_at=start + timedelta(minutes=index),
        steps=StepCount(steps),
        heart_rate=None if heart_rate is None else HeartRate(heart_rate),
        kind=kind,
        kind_code=80 if kind is ActivityKind.OTHER else None,
        intensity=Intensity(0),
    )
