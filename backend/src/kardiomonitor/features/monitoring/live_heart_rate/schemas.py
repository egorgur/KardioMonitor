"""Контракт API слайса «Пульс сейчас»."""

from collections.abc import Sequence
from typing import Annotated, Self

from pydantic import AwareDatetime, Field

from kardiomonitor.features.monitoring.domain.heart_rate import MAX_BPM, MIN_BPM, HeartRate
from kardiomonitor.features.monitoring.domain.live_heart_rate import LiveHeartRateReading
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime


class LiveHeartRateIn(ApiModel):
    """Одно значение непрерывного измерения."""

    measured_at: AwareDatetime
    heart_rate: Annotated[int, Field(ge=MIN_BPM, le=MAX_BPM)]

    def to_domain(self) -> LiveHeartRateReading:
        return LiveHeartRateReading(self.measured_at, HeartRate(self.heart_rate))


class LiveHeartRateReadingOut(ApiModel):
    measured_at: UtcDatetime
    heart_rate: int

    @classmethod
    def from_domain(cls, reading: LiveHeartRateReading) -> Self:
        return cls(measured_at=reading.measured_at, heart_rate=reading.heart_rate.bpm)


class LiveHeartRateWindowOut(ApiModel):
    """Замеры за последние `window_seconds` секунд, по порядку."""

    window_seconds: int
    readings: list[LiveHeartRateReadingOut]

    @classmethod
    def from_domain(cls, window_seconds: int, readings: Sequence[LiveHeartRateReading]) -> Self:
        return cls(
            window_seconds=window_seconds,
            readings=[LiveHeartRateReadingOut.from_domain(reading) for reading in readings],
        )
