"""Итоги дня — те же правила, что у `DailySummary` в Android."""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Self

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute, StepCount
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate


@dataclass(frozen=True, slots=True)
class DailySummary:
    """Итоги по минутам одного дня пациента."""

    recorded_minutes: int
    steps: StepCount
    average_heart_rate: HeartRate | None
    min_heart_rate: HeartRate | None
    max_heart_rate: HeartRate | None
    heart_rate_coverage_percent: int
    not_worn_minutes: int

    @classmethod
    def of(cls, minutes: Sequence[ActivityMinute]) -> Self:
        heart_rates = [m.heart_rate.bpm for m in minutes if m.heart_rate is not None]
        return cls(
            recorded_minutes=len(minutes),
            steps=StepCount(sum(minute.steps.value for minute in minutes)),
            # среднее округляется вниз — как в Android и в прототипе
            average_heart_rate=HeartRate(sum(heart_rates) // len(heart_rates))
            if heart_rates
            else None,
            min_heart_rate=HeartRate(min(heart_rates)) if heart_rates else None,
            max_heart_rate=HeartRate(max(heart_rates)) if heart_rates else None,
            heart_rate_coverage_percent=100 * len(heart_rates) // len(minutes) if minutes else 0,
            not_worn_minutes=sum(1 for minute in minutes if not minute.is_worn),
        )
