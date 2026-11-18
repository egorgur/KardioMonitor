"""Контракт API слайса «План реабилитации» (api-contract.md, «План реабилитации»).

Правила значений проверяет домен: клиент получает точный `code`, а не `invalid_request`.
"""

from datetime import date, time
from typing import Self

from pydantic import StrictInt

from kardiomonitor.features.rehab.domain.daily_targets import DailyTargets, TrainingHeartRateZone
from kardiomonitor.features.rehab.domain.monitoring_settings import MonitoringSettings
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from kardiomonitor.features.rehab.domain.rehab_program import RehabProgram, RehabStage
from kardiomonitor.platform.web.api_model import ApiModel


class TrainingZoneIn(ApiModel):
    lower_bpm: int
    upper_bpm: int


class DailyTargetsIn(ApiModel):
    training_zone: TrainingZoneIn
    training_minutes: int
    steps: int

    def to_domain(self) -> DailyTargets:
        zone = TrainingHeartRateZone(self.training_zone.lower_bpm, self.training_zone.upper_bpm)
        return DailyTargets(zone, self.training_minutes, self.steps)


class RehabStageIn(ApiModel):
    first_day: int
    last_day: int
    targets: DailyTargetsIn

    def to_domain(self) -> RehabStage:
        return RehabStage(self.first_day, self.last_day, self.targets.to_domain())


class RehabProgramIn(ApiModel):
    start_date: date
    duration_days: int
    stages: list[RehabStageIn]

    def to_domain(self) -> RehabProgram:
        stages = tuple(stage.to_domain() for stage in self.stages)
        return RehabProgram(self.start_date, self.duration_days, stages)


class MonitoringSettingsIn(ApiModel):
    walking_steps_threshold: int
    high_heart_rate_threshold: int
    low_activity_steps: int
    daily_alert_time_utc: time
    training_minutes_upper_threshold: StrictInt | None = None

    def to_domain(self) -> MonitoringSettings:
        return MonitoringSettings(
            walking_steps_threshold=self.walking_steps_threshold,
            high_heart_rate_threshold=self.high_heart_rate_threshold,
            low_activity_steps=self.low_activity_steps,
            daily_alert_time_utc=self.daily_alert_time_utc,
            training_minutes_upper_threshold=self.training_minutes_upper_threshold,
        )


class RehabPlanIn(ApiModel):
    """Программа и параметры наблюдения целиком; действуют с сегодняшнего дня пациента."""

    program: RehabProgramIn
    monitoring_settings: MonitoringSettingsIn


class TrainingZoneOut(ApiModel):
    lower_bpm: int
    upper_bpm: int


class DailyTargetsOut(ApiModel):
    training_zone: TrainingZoneOut
    training_minutes: int
    steps: int

    @classmethod
    def from_domain(cls, targets: DailyTargets) -> Self:
        zone = targets.training_zone
        return cls(
            training_zone=TrainingZoneOut(lower_bpm=zone.lower_bpm, upper_bpm=zone.upper_bpm),
            training_minutes=targets.training_minutes,
            steps=targets.steps,
        )


class RehabStageOut(ApiModel):
    first_day: int
    last_day: int
    targets: DailyTargetsOut


class RehabProgramOut(ApiModel):
    start_date: date
    duration_days: int
    stages: list[RehabStageOut]

    @classmethod
    def from_domain(cls, program: RehabProgram) -> Self:
        stages = [
            RehabStageOut(
                first_day=stage.first_day,
                last_day=stage.last_day,
                targets=DailyTargetsOut.from_domain(stage.targets),
            )
            for stage in program.stages
        ]
        return cls(
            start_date=program.start_date, duration_days=program.duration_days, stages=stages
        )


class MonitoringSettingsOut(ApiModel):
    training_minutes_upper_threshold: int | None
    walking_steps_threshold: int
    high_heart_rate_threshold: int
    low_activity_steps: int
    daily_alert_time_utc: time


class RehabPlanOut(ApiModel):
    """Текущая версия плана. `is_default` — план не сохранялся (программа по умолчанию)."""

    time_zone: str
    is_default: bool
    effective_from: date
    program: RehabProgramOut
    monitoring_settings: MonitoringSettingsOut

    @classmethod
    def from_domain(cls, plan: RehabPlan, *, time_zone: str, is_default: bool) -> Self:
        current = plan.current
        settings = current.settings
        return cls(
            time_zone=time_zone,
            is_default=is_default,
            effective_from=current.effective_from,
            program=RehabProgramOut.from_domain(current.program),
            monitoring_settings=MonitoringSettingsOut(
                walking_steps_threshold=settings.walking_steps_threshold,
                high_heart_rate_threshold=settings.high_heart_rate_threshold,
                low_activity_steps=settings.low_activity_steps,
                daily_alert_time_utc=settings.daily_alert_time_utc,
                training_minutes_upper_threshold=settings.training_minutes_upper_threshold,
            ),
        )
