"""Распределение пульса по интервалам 10 уд/мин (ТР-04)."""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Self

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute

BAND_WIDTH_BPM = 10
LOWEST_BAND_FROM_BPM = 40
"""Ниже — один интервал «< 40»."""
HIGHEST_BAND_FROM_BPM = 120
"""Выше — один интервал «120+»."""


@dataclass(frozen=True, slots=True)
class HeartRateBand:
    """Интервал пульса и минуты в нём. `from_bpm` None — «ниже 40», `to_bpm` None — «120 и выше»."""

    from_bpm: int | None
    to_bpm: int | None
    minutes: int


@dataclass(frozen=True, slots=True)
class HeartRateDistribution:
    """Сколько минут пульс был в каждом интервале. Минуты без пульса не учитываются."""

    bands: tuple[HeartRateBand, ...]

    @classmethod
    def of(cls, minutes: Sequence[ActivityMinute]) -> Self:
        counts = [0] * (len(_band_starts()) + 1)
        for minute in minutes:
            if minute.heart_rate is not None:
                counts[_band_index(minute.heart_rate.bpm)] += 1
        return cls(tuple(_band(index, count) for index, count in enumerate(counts)))


def _band_starts() -> range:
    return range(LOWEST_BAND_FROM_BPM, HIGHEST_BAND_FROM_BPM + 1, BAND_WIDTH_BPM)


def _band_index(bpm: int) -> int:
    """0 — «< 40», затем по интервалу на каждые 10 уд/мин, последний — «120+»."""
    if bpm < LOWEST_BAND_FROM_BPM:
        return 0
    capped = min(bpm, HIGHEST_BAND_FROM_BPM)
    return (capped - LOWEST_BAND_FROM_BPM) // BAND_WIDTH_BPM + 1


def _band(index: int, minutes: int) -> HeartRateBand:
    if index == 0:
        return HeartRateBand(None, LOWEST_BAND_FROM_BPM - 1, minutes)
    from_bpm = LOWEST_BAND_FROM_BPM + (index - 1) * BAND_WIDTH_BPM
    if from_bpm >= HIGHEST_BAND_FROM_BPM:
        return HeartRateBand(from_bpm, None, minutes)
    return HeartRateBand(from_bpm, from_bpm + BAND_WIDTH_BPM - 1, minutes)
