"""Превышение строгое, по историческим настройкам и правилам тренировочной минуты."""

from dataclasses import replace
from datetime import date, datetime, timedelta

import pytest

from kardiomonitor.features.alerts.domain.patient_alert import AlertKind, TrainingMinutesExceeded
from kardiomonitor.features.alerts.domain.training_minutes_alert import training_minutes_alert
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from tests.features.monitoring.minutes import minute
from tests.features.rehab.plans import MOSCOW, PATIENT, TODAY_NOON, program, targets

DAY = date(2026, 10, 12)
START = datetime(2026, 10, 12, 8, tzinfo=MOSCOW)


def _plan(threshold: int | None) -> RehabPlan:
    plan = RehabPlan.default_for(PATIENT)
    settings = replace(plan.current.settings, training_minutes_upper_threshold=threshold)
    return plan.revised(plan.current.program, settings, today=date(2026, 10, 1))


@pytest.mark.parametrize(
    ("count", "threshold", "exceeded"),
    [
        (59, 60, False),
        (60, 60, False),
        (61, 60, True),
        (61, None, False),
    ],
)
def test_порог_60_превышен_только_при_61_минуте(
    count: int,
    threshold: int | None,
    *,
    exceeded: bool,
) -> None:
    minutes = [minute(i, steps=80, heart_rate=100, start=START) for i in range(count)]

    alert = training_minutes_alert(PATIENT, _plan(threshold), DAY, minutes, now=TODAY_NOON)

    assert (alert is not None) is exceeded
    if alert is not None:
        assert alert.kind is AlertKind.TRAINING_MINUTES_EXCEEDED
        assert alert.day == DAY
        assert alert.details == TrainingMinutesExceeded(61, 60)


def test_поздний_день_использует_свой_порог_зону_и_порог_ходьбы() -> None:
    plan = _plan(60)
    changed = replace(
        plan.current.settings, training_minutes_upper_threshold=100, walking_steps_threshold=100
    )
    plan = plan.revised(
        program((1, 25, targets(zone=(120, 140)))), changed, today=DAY + timedelta(days=1)
    )
    minutes = [minute(i, steps=80, heart_rate=100, start=START) for i in range(61)]

    alert = training_minutes_alert(PATIENT, plan, DAY, minutes, now=TODAY_NOON)

    assert alert is not None
    assert alert.details == TrainingMinutesExceeded(61, 60)


def test_день_вне_своей_программы_не_оценивается() -> None:
    plan = _plan(1)
    day = date(2026, 12, 1)
    minutes = [minute(i, steps=80, heart_rate=100, start=START) for i in range(2)]

    assert training_minutes_alert(PATIENT, plan, day, minutes, now=TODAY_NOON) is None


def test_минуты_без_тренировочного_пульса_или_ходьбы_не_считаются() -> None:
    minutes = [
        minute(0, steps=60, heart_rate=100, start=START),
        minute(1, steps=80, start=START),
        minute(2, steps=80, heart_rate=120, start=START),
        minute(3, steps=80, heart_rate=100, start=START),
    ]

    assert training_minutes_alert(PATIENT, _plan(1), DAY, minutes, now=TODAY_NOON) is None
