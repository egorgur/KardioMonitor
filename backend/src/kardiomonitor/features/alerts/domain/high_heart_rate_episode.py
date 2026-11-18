"""Эпизод высокого пульса: замеры выше порога без перерыва больше 5 минут (ТР-08, п. 5)."""

from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Self

from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.shared_kernel import DomainRuleViolation

MAX_EPISODE_BREAK = timedelta(minutes=5)
"""Перерыв больше — новый эпизод. Предположение В-15 (docs/requirements.md)."""
_MINUTE = timedelta(minutes=1)


@dataclass(frozen=True, slots=True)
class HeartRatePoint:
    """Замер пульса: минута активности (момент — её начало) или «пульс сейчас»."""

    at: datetime
    heart_rate: HeartRate


@dataclass(frozen=True, slots=True)
class HighHeartRateEpisode:
    """Замеры выше порога подряд: с `started_at` по `last_at` (начало последней минуты или
    момент последнего замера), максимум и порог, при котором эпизод начался.

    Проверяется при любой активности, не только при ходьбе (В-15).
    """

    started_at: datetime
    last_at: datetime
    max_heart_rate: HeartRate
    threshold_bpm: int

    def __post_init__(self) -> None:
        if self.started_at.tzinfo is None or self.last_at.tzinfo is None:
            raise DomainRuleViolation("время эпизода без часового пояса", code="naive_datetime")
        if self.last_at < self.started_at:
            raise ValueError("эпизод заканчивается раньше, чем начинается")

    @property
    def duration_minutes(self) -> int:
        """Минут от начала первой до конца последней минуты эпизода: 10:01–10:06 — 6 минут."""
        return (self.last_at - self.started_at + _MINUTE) // _MINUTE

    def continues(self, other: "HighHeartRateEpisode") -> bool:
        """Эпизоды — один, если между ними перерыв не больше 5 минут (или они пересекаются)."""
        return (
            other.started_at - self.last_at - _MINUTE <= MAX_EPISODE_BREAK
            and self.started_at - other.last_at - _MINUTE <= MAX_EPISODE_BREAK
        )

    def joined(self, other: "HighHeartRateEpisode") -> Self:
        """Один эпизод из двух; порог — того, что начался раньше."""
        first = self if self.started_at <= other.started_at else other
        return type(self)(
            started_at=first.started_at,
            last_at=max(self.last_at, other.last_at),
            max_heart_rate=max(self.max_heart_rate, other.max_heart_rate),
            threshold_bpm=first.threshold_bpm,
        )


def high_heart_rate_episodes(
    points: Iterable[HeartRatePoint], threshold_at: Callable[[datetime], int]
) -> list[HighHeartRateEpisode]:
    """Эпизоды среди замеров; `threshold_at` — порог высокого пульса, действовавший в момент."""
    episodes: list[HighHeartRateEpisode] = []
    for point in sorted(points, key=lambda point: point.at):
        threshold = threshold_at(point.at)
        if point.heart_rate.bpm <= threshold:
            continue
        episode = HighHeartRateEpisode(point.at, point.at, point.heart_rate, threshold)
        if episodes and episodes[-1].continues(episode):
            episodes[-1] = episodes[-1].joined(episode)
        else:
            episodes.append(episode)
    return episodes
