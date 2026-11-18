"""Контракт API слайса «Нормы для телефона» (api-contract.md)."""

from datetime import date
from typing import Self

from kardiomonitor.features.rehab.domain.daily_targets import DailyTargets
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from kardiomonitor.features.rehab.domain.rehab_program import RehabStage
from kardiomonitor.platform.web.api_model import ApiModel


class PhoneTrainingZoneOut(ApiModel):
    lower_bpm: int
    upper_bpm: int


class PhoneTargetsOut(ApiModel):
    training_zone: PhoneTrainingZoneOut
    training_minutes: int
    steps: int

    @classmethod
    def from_domain(cls, targets: DailyTargets) -> Self:
        zone = targets.training_zone
        return cls(
            training_zone=PhoneTrainingZoneOut(lower_bpm=zone.lower_bpm, upper_bpm=zone.upper_bpm),
            training_minutes=targets.training_minutes,
            steps=targets.steps,
        )


class PhoneStageOut(ApiModel):
    first_day: int
    last_day: int
    targets: PhoneTargetsOut

    @classmethod
    def from_domain(cls, stage: RehabStage) -> Self:
        return cls(
            first_day=stage.first_day,
            last_day=stage.last_day,
            targets=PhoneTargetsOut.from_domain(stage.targets),
        )


class PhoneProgramOut(ApiModel):
    start_date: date
    duration_days: int
    stages: list[PhoneStageOut]


class PhoneRehabPlanOut(ApiModel):
    """Только то, что телефону нужно для показа нормы дня пациенту."""

    time_zone: str
    effective_from: date
    walking_steps_threshold: int
    program: PhoneProgramOut

    @classmethod
    def from_domain(cls, plan: RehabPlan, *, time_zone: str) -> Self:
        current = plan.current
        program = current.program
        return cls(
            time_zone=time_zone,
            effective_from=current.effective_from,
            walking_steps_threshold=current.settings.walking_steps_threshold,
            program=PhoneProgramOut(
                start_date=program.start_date,
                duration_days=program.duration_days,
                stages=[PhoneStageOut.from_domain(stage) for stage in program.stages],
            ),
        )
