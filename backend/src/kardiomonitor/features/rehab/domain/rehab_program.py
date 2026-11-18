"""Программа реабилитации: дата начала, длительность и этапы с нормами (ТР-01)."""

from dataclasses import dataclass
from datetime import date, timedelta
from typing import NewType, NoReturn, Self

from kardiomonitor.features.rehab.domain.daily_targets import DEFAULT_DAILY_TARGETS, DailyTargets
from kardiomonitor.shared_kernel import DomainRuleViolation

ProgramDay = NewType("ProgramDay", int)
"""Номер дня от начала программы: дата начала — день 1."""

MAX_PROGRAM_DAYS = 365
"""Предел — предположение В-02."""
DEFAULT_PROGRAM_DAYS = 42
"""Длительность программы нового пациента — предположение В-02."""


@dataclass(frozen=True, slots=True)
class RehabStage:
    """Дни программы `first_day`–`last_day` (включительно) с одними нормами."""

    first_day: int
    last_day: int
    targets: DailyTargets

    def __post_init__(self) -> None:
        if not 1 <= self.first_day <= self.last_day:
            raise DomainRuleViolation(
                f"этап {self.first_day}–{self.last_day}: первый день — не меньше 1 "
                "и не позже последнего",
                code="invalid_rehab_stage",
            )

    def covers(self, day: ProgramDay) -> bool:
        return self.first_day <= day <= self.last_day


@dataclass(frozen=True, slots=True)
class RehabProgram:
    """Этапы идут подряд от дня 1 до последнего дня; хранятся по порядку."""

    start_date: date
    duration_days: int
    stages: tuple[RehabStage, ...]

    def __post_init__(self) -> None:
        if not 1 <= self.duration_days <= MAX_PROGRAM_DAYS:
            raise DomainRuleViolation(
                f"длительность программы — от 1 до {MAX_PROGRAM_DAYS} дней: {self.duration_days}",
                code="invalid_program_duration",
            )
        ordered = tuple(sorted(self.stages, key=lambda stage: stage.first_day))
        _check_stages_cover(ordered, self.duration_days)
        object.__setattr__(self, "stages", ordered)

    @classmethod
    def default(cls, start_date: date) -> Self:
        """Программа нового пациента: один этап с нормами по умолчанию (ТР-01, п. 5)."""
        stage = RehabStage(1, DEFAULT_PROGRAM_DAYS, DEFAULT_DAILY_TARGETS)
        return cls(start_date, DEFAULT_PROGRAM_DAYS, (stage,))

    @property
    def last_date(self) -> date:
        return self.start_date + timedelta(days=self.duration_days - 1)

    def day_number(self, day: date) -> ProgramDay | None:
        """Номер дня в программе; None — день вне программы."""
        number = (day - self.start_date).days + 1
        return ProgramDay(number) if 1 <= number <= self.duration_days else None

    def date_of(self, day: ProgramDay) -> date:
        return self.start_date + timedelta(days=day - 1)

    def stage_on(self, day: date) -> RehabStage | None:
        number = self.day_number(day)
        if number is None:
            return None
        return next(stage for stage in self.stages if stage.covers(number))

    def targets_on(self, day: date) -> DailyTargets | None:
        """Нормы дня; None — день вне программы."""
        stage = self.stage_on(day)
        return None if stage is None else stage.targets


def _check_stages_cover(stages: tuple[RehabStage, ...], duration_days: int) -> None:
    """Этапы по порядку покрывают дни 1…duration_days без пропусков и пересечений."""
    next_day = 1
    for stage in stages:
        if stage.last_day > duration_days:
            _stages_error(
                f"этап {stage.first_day}–{stage.last_day} выходит за программу из "
                f"{duration_days} дней"
            )
        if stage.first_day > next_day:
            _stages_error(f"дни {next_day}–{stage.first_day - 1} не входят ни в один этап")
        if stage.first_day < next_day:
            _stages_error(f"дни {stage.first_day}–{next_day - 1} входят в два этапа")
        next_day = stage.last_day + 1
    if next_day <= duration_days:
        _stages_error(f"дни {next_day}–{duration_days} не входят ни в один этап")


def _stages_error(message: str) -> NoReturn:
    raise DomainRuleViolation(message, code="invalid_program_stages")
