"""Контракт API слайса «День пациента»."""

from collections.abc import Sequence
from datetime import date
from typing import Self

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityKind, ActivityMinute
from kardiomonitor.features.monitoring.domain.daily_summary import DailySummary
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime


class DailySummaryOut(ApiModel):
    recorded_minutes: int
    steps: int
    average_heart_rate: int | None
    min_heart_rate: int | None
    max_heart_rate: int | None
    heart_rate_coverage_percent: int
    not_worn_minutes: int

    @classmethod
    def from_domain(cls, summary: DailySummary) -> Self:
        return cls(
            recorded_minutes=summary.recorded_minutes,
            steps=summary.steps.value,
            average_heart_rate=_bpm(summary.average_heart_rate),
            min_heart_rate=_bpm(summary.min_heart_rate),
            max_heart_rate=_bpm(summary.max_heart_rate),
            heart_rate_coverage_percent=summary.heart_rate_coverage_percent,
            not_worn_minutes=summary.not_worn_minutes,
        )


class DayMinuteOut(ApiModel):
    started_at: UtcDatetime
    steps: int
    heart_rate: int | None
    kind: ActivityKind

    @classmethod
    def from_domain(cls, minute: ActivityMinute) -> Self:
        return cls(
            started_at=minute.started_at,
            steps=minute.steps.value,
            heart_rate=_bpm(minute.heart_rate),
            kind=minute.kind,
        )


class PatientDayOut(ApiModel):
    """День в часовом поясе пациента: итоги и минуты по порядку."""

    day: date
    time_zone: str
    summary: DailySummaryOut
    minutes: list[DayMinuteOut]

    @classmethod
    def from_domain(cls, day: date, time_zone: str, minutes: Sequence[ActivityMinute]) -> Self:
        return cls(
            day=day,
            time_zone=time_zone,
            summary=DailySummaryOut.from_domain(DailySummary.of(minutes)),
            minutes=[DayMinuteOut.from_domain(minute) for minute in minutes],
        )


def _bpm(heart_rate: HeartRate | None) -> int | None:
    return None if heart_rate is None else heart_rate.bpm
