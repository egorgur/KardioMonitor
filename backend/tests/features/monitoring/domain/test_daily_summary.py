"""Итоги дня — те же данные и ожидания, что в Android `DailySummaryTest`."""

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityKind, StepCount
from kardiomonitor.features.monitoring.domain.daily_summary import DailySummary
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from tests.features.monitoring.minutes import minute


def test_шаги_дня_это_сумма_шагов_всех_минут() -> None:
    summary = DailySummary.of([minute(0, steps=5), minute(1, steps=12), minute(2)])

    assert summary.steps == StepCount(17)


def test_средний_минимальный_и_максимальный_пульс_считаются_только_по_минутам_с_замером() -> None:
    summary = DailySummary.of([minute(0, heart_rate=60), minute(1), minute(2, heart_rate=81)])

    assert summary.average_heart_rate == HeartRate(70)
    assert summary.min_heart_rate == HeartRate(60)
    assert summary.max_heart_rate == HeartRate(81)


def test_покрытие_пульсом_это_доля_минут_с_замером_в_процентах() -> None:
    summary = DailySummary.of([minute(0, heart_rate=70), minute(1), minute(2)])

    assert summary.heart_rate_coverage_percent == 33
    assert summary.recorded_minutes == 3


def test_снятый_и_заряжающийся_браслет_считаются_минутами_без_браслета() -> None:
    minutes = [
        minute(0, kind=ActivityKind.NOT_WORN),
        minute(1, kind=ActivityKind.CHARGING),
        minute(2, kind=ActivityKind.SLEEP),
    ]

    assert DailySummary.of(minutes).not_worn_minutes == 2


def test_день_без_замеров_пульса_не_имеет_среднего_пульса() -> None:
    summary = DailySummary.of([minute(0, steps=3)])

    assert summary.average_heart_rate is None
    assert summary.heart_rate_coverage_percent == 0


def test_день_без_записей_не_содержит_данных() -> None:
    summary = DailySummary.of([])

    assert summary.recorded_minutes == 0
    assert summary.steps == StepCount(0)
