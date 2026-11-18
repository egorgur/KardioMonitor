"""Контракт API слайса «Прогресс реабилитации» (api-contract.md)."""

from datetime import date
from typing import Self

from kardiomonitor.features.rehab.domain.rehab_progress import (
    CurrentStage,
    ProgramDayResult,
    ProgramDayStatus,
    RehabProgress,
)
from kardiomonitor.platform.web.api_model import ApiModel


class CurrentStageOut(ApiModel):
    number: int
    first_day: int
    last_day: int

    @classmethod
    def from_domain(cls, current: CurrentStage) -> Self:
        stage = current.stage
        return cls(number=current.number, first_day=stage.first_day, last_day=stage.last_day)


class ProgramDayOut(ApiModel):
    day: date
    program_day: int
    status: ProgramDayStatus

    @classmethod
    def from_domain(cls, result: ProgramDayResult) -> Self:
        return cls(day=result.day, program_day=result.program_day, status=result.status)


class RehabProgressOut(ApiModel):
    """`program_day` null — программа не началась; сегодня — `in_progress`, не в счёте."""

    start_date: date
    duration_days: int
    program_day: int | None
    is_finished: bool
    completed_days: int
    met_days: int
    current_stage: CurrentStageOut | None
    days: list[ProgramDayOut]

    @classmethod
    def from_domain(cls, progress: RehabProgress) -> Self:
        stage = progress.current_stage
        return cls(
            start_date=progress.program.start_date,
            duration_days=progress.duration_days,
            program_day=progress.program_day,
            is_finished=progress.is_finished,
            completed_days=progress.completed_days,
            met_days=progress.met_days,
            current_stage=None if stage is None else CurrentStageOut.from_domain(stage),
            days=[ProgramDayOut.from_domain(day) for day in progress.days],
        )
