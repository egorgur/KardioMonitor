"""Программа реабилитации и её этапы — ТР-01, критерии приёмки из docs/requirements.md."""

from datetime import date, timedelta

import pytest

from kardiomonitor.features.rehab.domain.daily_targets import (
    DEFAULT_DAILY_TARGETS,
    DailyTargets,
    TrainingHeartRateZone,
)
from kardiomonitor.features.rehab.domain.rehab_program import (
    DEFAULT_PROGRAM_DAYS,
    ProgramDay,
    RehabProgram,
    RehabStage,
)
from kardiomonitor.shared_kernel import DomainRuleViolation
from tests.features.rehab.plans import START, program, targets


def test_нормы_по_умолчанию_90_110_уд_мин_30_минут_10000_шагов() -> None:
    assert DailyTargets(TrainingHeartRateZone(90, 110), 30, 10_000) == DEFAULT_DAILY_TARGETS


def test_программа_по_умолчанию_один_этап_на_всю_длительность() -> None:
    default = RehabProgram.default(START)

    assert default.stages == (RehabStage(1, DEFAULT_PROGRAM_DAYS, DEFAULT_DAILY_TARGETS),)


def test_день_16_оценивается_нормами_второго_этапа() -> None:
    assert program().targets_on(START + timedelta(days=15)) == targets(40)


def test_день_программы_считается_от_даты_начала() -> None:
    assert program().day_number(START) == ProgramDay(1)
    assert program().day_number(date(2026, 10, 25)) == ProgramDay(25)


def test_дни_вне_программы_не_имеют_номера_и_норм() -> None:
    assert program().day_number(START - timedelta(days=1)) is None
    assert program().targets_on(date(2026, 10, 26)) is None


def test_пропуск_дней_между_этапами_это_ошибка_с_номерами_дней() -> None:
    with pytest.raises(DomainRuleViolation, match="дни 16–17 не входят ни в один этап") as error:
        program((1, 15, targets()), (18, 25, targets()))

    assert error.value.code == "invalid_program_stages"


def test_пересечение_этапов_это_ошибка() -> None:
    with pytest.raises(DomainRuleViolation, match="дни 15–16 входят в два этапа"):
        program((1, 16, targets()), (15, 25, targets()))


def test_этапы_должны_доходить_до_последнего_дня_программы() -> None:
    with pytest.raises(DomainRuleViolation, match="дни 21–25 не входят ни в один этап"):
        program((1, 20, targets()))


def test_этап_за_пределами_программы_это_ошибка() -> None:
    with pytest.raises(DomainRuleViolation, match="этап 20–30 выходит за программу из 25 дней"):
        program((1, 19, targets()), (20, 30, targets()))


def test_этапы_можно_передать_не_по_порядку() -> None:
    reordered = program((16, 25, targets(40)), (1, 15, targets(30)))

    assert [stage.first_day for stage in reordered.stages] == [1, 16]


@pytest.mark.parametrize("days", [0, 366])
def test_длительность_программы_от_1_до_365_дней(days: int) -> None:
    with pytest.raises(DomainRuleViolation) as error:
        RehabProgram(START, days, (RehabStage(1, 1, targets()),))

    assert error.value.code == "invalid_program_duration"


def test_нижняя_граница_зоны_должна_быть_меньше_верхней() -> None:
    with pytest.raises(DomainRuleViolation, match="нижняя граница должна быть меньше верхней"):
        TrainingHeartRateZone(120, 100)


@pytest.mark.parametrize(("lower", "upper"), [(39, 100), (100, 221)])
def test_зона_пульса_в_пределах_40_220(lower: int, upper: int) -> None:
    with pytest.raises(DomainRuleViolation) as error:
        TrainingHeartRateZone(lower, upper)

    assert error.value.code == "invalid_training_zone"


@pytest.mark.parametrize(
    ("minutes", "steps"), [(0, 10_000), (1441, 10_000), (30, -1), (30, 100_001)]
)
def test_нормы_минут_1_1440_и_шагов_0_100000(minutes: int, steps: int) -> None:
    with pytest.raises(DomainRuleViolation) as error:
        targets(minutes, steps)

    assert error.value.code == "invalid_daily_targets"


def test_этап_начинается_не_позже_чем_заканчивается() -> None:
    with pytest.raises(DomainRuleViolation) as error:
        RehabStage(5, 4, targets())

    assert error.value.code == "invalid_rehab_stage"
