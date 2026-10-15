"""Контракт API слайса «Приём минут» (api-contract.md)."""

from typing import Annotated, Self

from pydantic import AwareDatetime, Field

from kardiomonitor.features.monitoring.domain.activity_minute import (
    ActivityKind,
    ActivityMinute,
    Intensity,
    StepCount,
)
from kardiomonitor.features.monitoring.domain.heart_rate import MAX_BPM, MIN_BPM, HeartRate
from kardiomonitor.features.monitoring.domain.minute_batch import MAX_BATCH_MINUTES, MinuteBatch
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime

from .upload_minutes import UploadReceipt


class ActivityMinuteIn(ApiModel):
    """Минута активности с телефона."""

    started_at: AwareDatetime
    steps: Annotated[int, Field(ge=0)]
    heart_rate: Annotated[int | None, Field(ge=MIN_BPM, le=MAX_BPM)] = None
    kind: ActivityKind
    kind_code: Annotated[int | None, Field(ge=0, le=255)] = None
    intensity: Annotated[int, Field(ge=0, le=255)]

    def to_domain(self) -> ActivityMinute:
        return ActivityMinute(
            started_at=self.started_at,
            steps=StepCount(self.steps),
            heart_rate=None if self.heart_rate is None else HeartRate(self.heart_rate),
            kind=self.kind,
            kind_code=self.kind_code,
            intensity=Intensity(self.intensity),
        )


class MinuteBatchIn(ApiModel):
    """Пакет минут: 1–10 080 (7 суток)."""

    minutes: Annotated[list[ActivityMinuteIn], Field(min_length=1, max_length=MAX_BATCH_MINUTES)]

    def to_domain(self) -> MinuteBatch:
        return MinuteBatch(tuple(minute.to_domain() for minute in self.minutes))


class UploadReceiptOut(ApiModel):
    """Квитанция: `last_minute_started_at` — откуда телефону продолжать."""

    received: int
    new: int
    last_minute_started_at: UtcDatetime

    @classmethod
    def from_domain(cls, receipt: UploadReceipt) -> Self:
        return cls(
            received=receipt.received,
            new=receipt.new,
            last_minute_started_at=receipt.last_minute_started_at,
        )
