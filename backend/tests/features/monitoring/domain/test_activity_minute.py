"""Правила минуты активности, пульса и пакета минут."""

from datetime import UTC, datetime
from zoneinfo import ZoneInfo

import pytest

from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.features.monitoring.domain.minute_batch import MAX_BATCH_MINUTES, MinuteBatch
from kardiomonitor.shared_kernel import DomainRuleViolation
from tests.features.monitoring.minutes import minute


@pytest.mark.parametrize("bpm", [11, 249])
def test_пульс_на_границах_диапазона_допустим(bpm: int) -> None:
    assert HeartRate(bpm).bpm == bpm


@pytest.mark.parametrize("bpm", [0, 10, 250, 255])
def test_пульс_вне_11_249_не_принимается(bpm: int) -> None:
    with pytest.raises(DomainRuleViolation) as error:
        HeartRate(bpm)

    assert error.value.code == "heart_rate_out_of_range"


def test_минута_без_часового_пояса_не_создаётся() -> None:
    with pytest.raises(DomainRuleViolation) as error:
        minute(0, start=datetime(2026, 10, 9))  # noqa: DTZ001 — проверяем именно время без пояса

    assert error.value.code == "naive_datetime"


def test_минута_начинается_с_нулевой_секунды() -> None:
    with pytest.raises(DomainRuleViolation) as error:
        minute(0, start=datetime(2026, 10, 9, 10, 0, 30, tzinfo=UTC))

    assert error.value.code == "minute_not_aligned"


def test_минута_в_часовом_поясе_телефона_допустима() -> None:
    start = datetime(2026, 10, 9, 23, 59, tzinfo=ZoneInfo("Asia/Novosibirsk"))

    assert minute(0, start=start).started_at == start


def test_пустой_пакет_минут_не_принимается() -> None:
    with pytest.raises(DomainRuleViolation) as error:
        MinuteBatch(())

    assert error.value.code == "empty_minute_batch"


def test_пакет_больше_семи_суток_не_принимается() -> None:
    minutes = tuple(minute(index) for index in range(MAX_BATCH_MINUTES + 1))

    with pytest.raises(DomainRuleViolation) as error:
        MinuteBatch(minutes)

    assert error.value.code == "minute_batch_too_large"


def test_повтор_минуты_внутри_пакета_не_принимается() -> None:
    with pytest.raises(DomainRuleViolation) as error:
        MinuteBatch((minute(0, steps=1), minute(0, steps=2)))

    assert error.value.code == "duplicate_minute"
