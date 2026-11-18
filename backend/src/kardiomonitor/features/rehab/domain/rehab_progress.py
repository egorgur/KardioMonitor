"""Прогресс реабилитации: «день X из Y» и в скольких завершённых днях норма выполнена (ТР-05)."""

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from enum import StrEnum
from typing import Self
from zoneinfo import ZoneInfo

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.monitoring.domain.patient_day import PatientDay
from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from kardiomonitor.features.rehab.domain.rehab_program import ProgramDay, RehabProgram, RehabStage


class ProgramDayStatus(StrEnum):
    MET = "met"
    NOT_MET = "not_met"
    NO_DATA = "no_data"
    """Минут нет — норма не выполнена (предположение В-01)."""
    IN_PROGRESS = "in_progress"
    """Сегодня: сутки не закончились, в счёт выполненных не идёт (предположение В-01)."""


@dataclass(frozen=True, slots=True)
class ProgramDayResult:
    day: date
    program_day: ProgramDay
    status: ProgramDayStatus


@dataclass(frozen=True, slots=True)
class CurrentStage:
    """Этап, который идёт сегодня; `number` — его номер в программе с 1."""

    number: int
    stage: RehabStage


@dataclass(frozen=True, slots=True)
class RehabProgress:
    """Прогресс по текущей версии программы; каждый день — по нормам своей версии (ADR-0010).

    `program_day` None — программа ещё не началась; после последнего дня — последний день и
    `is_finished`. `today` — выполнение нормы сегодня «пока»; None — сегодня вне программы.
    """

    program: RehabProgram
    program_day: ProgramDay | None
    is_finished: bool
    current_stage: CurrentStage | None
    days: tuple[ProgramDayResult, ...]
    today: DailyTargetProgress | None

    @staticmethod
    def period(plan: RehabPlan, *, time_zone: ZoneInfo, today: date) -> tuple[datetime, datetime]:
        """Какие минуты нужны `of`: [начало программы, конец сегодня или последнего дня)."""
        program = plan.current.program
        start = PatientDay(program.start_date, time_zone).start
        return start, max(start, PatientDay(min(today, program.last_date), time_zone).end)

    @classmethod
    def of(
        cls, plan: RehabPlan, minutes: Sequence[ActivityMinute], *, time_zone: ZoneInfo, today: date
    ) -> Self:
        """`minutes` — минуты прошедших дней программы, включая сегодняшний."""
        by_day: defaultdict[date, list[ActivityMinute]] = defaultdict(list)
        for minute in minutes:
            by_day[minute.started_at.astimezone(time_zone).date()].append(minute)
        outcomes = {
            day: progress
            for day in cls.elapsed_days(plan, today=today)
            if (progress := plan.progress_on(day, by_day[day], is_final=day < today)) is not None
        }
        return cls.from_outcomes(plan, outcomes, today=today)

    @classmethod
    def from_outcomes(
        cls, plan: RehabPlan, outcomes: Mapping[date, DailyTargetProgress], *, today: date
    ) -> Self:
        """По готовым итогам прошедших дней программы (кеш итогов дня, ТР-06, п. 4).

        Дня нет в `outcomes` — данных за него нет.
        """
        program = plan.current.program
        days = tuple(
            ProgramDayResult(day, ProgramDay(number), _status(outcomes.get(day), day, today))
            for number, day in enumerate(cls.elapsed_days(plan, today=today), start=1)
        )
        is_finished = today > program.last_date
        in_program = program.day_number(today) is not None
        return cls(
            program=program,
            program_day=ProgramDay(len(days)) if days else None,
            is_finished=is_finished,
            current_stage=None if is_finished else _current_stage(program, today),
            days=days,
            today=_today(plan, outcomes.get(today), today) if in_program else None,
        )

    @staticmethod
    def elapsed_days(plan: RehabPlan, *, today: date) -> list[date]:
        """Прошедшие дни текущей программы, включая сегодняшний."""
        program = plan.current.program
        return _dates(program.start_date, min(today, program.last_date))

    @property
    def duration_days(self) -> int:
        return self.program.duration_days

    @property
    def completed_days(self) -> int:
        return sum(1 for day in self.days if day.status is not ProgramDayStatus.IN_PROGRESS)

    @property
    def met_days(self) -> int:
        return sum(1 for day in self.days if day.status is ProgramDayStatus.MET)


def _dates(first: date, last: date) -> list[date]:
    return [first + timedelta(days=offset) for offset in range((last - first).days + 1)]


def _status(progress: DailyTargetProgress | None, day: date, today: date) -> ProgramDayStatus:
    if day == today:
        return ProgramDayStatus.IN_PROGRESS
    if progress is None or not progress.has_data:
        return ProgramDayStatus.NO_DATA
    return ProgramDayStatus.MET if progress.is_met else ProgramDayStatus.NOT_MET


def _today(
    plan: RehabPlan, progress: DailyTargetProgress | None, today: date
) -> DailyTargetProgress | None:
    """Норма сегодня «пока»; итога ещё нет — по пустому дню (нормы видны, данных нет)."""
    return progress if progress is not None else plan.progress_on(today, (), is_final=False)


def _current_stage(program: RehabProgram, today: date) -> CurrentStage | None:
    stage = program.stage_on(today)
    return None if stage is None else CurrentStage(program.stages.index(stage) + 1, stage)
