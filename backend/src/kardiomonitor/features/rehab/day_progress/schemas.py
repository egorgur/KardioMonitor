"""Контракт API слайса «Выполнение нормы дня» (api-contract.md)."""

from datetime import date
from typing import Self

from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from kardiomonitor.features.rehab.domain.daily_targets import DailyTargets
from kardiomonitor.features.rehab.domain.rehab_program import ProgramDay
from kardiomonitor.platform.web.api_model import ApiModel


class DayTrainingZoneOut(ApiModel):
    lower_bpm: int
    upper_bpm: int


class DayTargetsOut(ApiModel):
    training_zone: DayTrainingZoneOut
    training_minutes: int
    steps: int

    @classmethod
    def from_domain(cls, targets: DailyTargets) -> Self:
        zone = targets.training_zone
        return cls(
            training_zone=DayTrainingZoneOut(lower_bpm=zone.lower_bpm, upper_bpm=zone.upper_bpm),
            training_minutes=targets.training_minutes,
            steps=targets.steps,
        )


class TargetProgressOut(ApiModel):
    """`is_final: false` — сутки пациента не закончились, итог «пока»."""

    targets: DayTargetsOut
    training_minutes: int
    steps: int
    walking_minutes: int
    walking_minutes_without_heart_rate: int
    has_data: bool
    is_final: bool
    is_met: bool

    @classmethod
    def from_domain(cls, progress: DailyTargetProgress) -> Self:
        return cls(
            targets=DayTargetsOut.from_domain(progress.targets),
            training_minutes=progress.training_minutes,
            steps=progress.steps,
            walking_minutes=progress.walking_minutes,
            walking_minutes_without_heart_rate=progress.walking_minutes_without_heart_rate,
            has_data=progress.has_data,
            is_final=progress.is_final,
            is_met=progress.is_met,
        )


class DayTargetProgressOut(ApiModel):
    """День вне программы — `program_day` и `progress` null."""

    day: date
    program_day: int | None
    progress: TargetProgressOut | None

    @classmethod
    def from_domain(
        cls, day: date, program_day: ProgramDay | None, progress: DailyTargetProgress | None
    ) -> Self:
        return cls(
            day=day,
            program_day=program_day,
            progress=None if progress is None else TargetProgressOut.from_domain(progress),
        )
