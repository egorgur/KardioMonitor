"""Индивидуальный верхний порог не назначается автоматически."""

from dataclasses import replace
from typing import cast

import pytest

from kardiomonitor.features.rehab.domain.monitoring_settings import DEFAULT_MONITORING_SETTINGS
from kardiomonitor.shared_kernel import DomainRuleViolation


@pytest.mark.parametrize("threshold", [None, 1, 60, 1440])
def test_верхний_порог_отключён_или_целое_от_1_до_1440(threshold: int | None) -> None:
    settings = replace(DEFAULT_MONITORING_SETTINGS, training_minutes_upper_threshold=threshold)

    assert settings.training_minutes_upper_threshold == threshold
    assert DEFAULT_MONITORING_SETTINGS.training_minutes_upper_threshold is None


@pytest.mark.parametrize("threshold", [0, -1, 1441, True, 1.5])
def test_неверный_верхний_порог_отклоняется(threshold: int | float) -> None:
    with pytest.raises(DomainRuleViolation, match="порог тренировочных минут"):
        replace(DEFAULT_MONITORING_SETTINGS, training_minutes_upper_threshold=cast(int, threshold))
