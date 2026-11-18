"""Выполнение нормы за день (ТР-03). Те же правила — в Android."""

from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Self

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.rehab.domain.daily_targets import DailyTargets
from kardiomonitor.features.rehab.domain.walking_minute import (
    WalkingMinuteClass,
    walking_minute_class,
)


@dataclass(frozen=True, slots=True)
class DailyTargetProgress:
    """Тренировочные минуты и шаги дня против норм.

    `is_final` — сутки пациента закончились; иначе итог «пока». Норма выполнена, если данные
    есть и выполнены **обе** нормы: тренировочных минут и шагов.
    """

    targets: DailyTargets
    training_minutes: int
    steps: int
    walking_minutes: int
    walking_minutes_without_heart_rate: int
    has_data: bool
    is_final: bool

    @classmethod
    def of(
        cls,
        minutes: Sequence[ActivityMinute],
        targets: DailyTargets,
        *,
        walking_steps_threshold: int,
        is_final: bool,
    ) -> Self:
        classes = Counter(
            walking_minute_class(
                minute, zone=targets.training_zone, walking_steps_threshold=walking_steps_threshold
            )
            for minute in minutes
        )
        return cls(
            targets=targets,
            training_minutes=classes[WalkingMinuteClass.TRAINING],
            steps=sum(minute.steps.value for minute in minutes),
            walking_minutes=len(minutes) - classes[None],
            walking_minutes_without_heart_rate=classes[WalkingMinuteClass.NO_HEART_RATE],
            has_data=bool(minutes),
            is_final=is_final,
        )

    @property
    def is_met(self) -> bool:
        return (
            self.has_data
            and self.training_minutes >= self.targets.training_minutes
            and self.steps >= self.targets.steps
        )
