"""Минута ходьбы и тренировочная минута — ТР-02, критерии приёмки (зона 90–110, порог 60).

Те же случаи — в Android (этап 4, requirements-design.md «Правила, общие с телефоном»).
"""

import pytest

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityKind
from kardiomonitor.features.rehab.domain.daily_targets import TrainingHeartRateZone
from kardiomonitor.features.rehab.domain.walking_minute import (
    WalkingMinuteClass,
    walking_minute_class,
)
from tests.features.monitoring.minutes import minute

ZONE = TrainingHeartRateZone(90, 110)


def _class(
    steps: int,
    heart_rate: int | None,
    *,
    threshold: int = 60,
    kind: ActivityKind = ActivityKind.OTHER,
) -> WalkingMinuteClass | None:
    observed = minute(0, steps=steps, heart_rate=heart_rate, kind=kind)
    return walking_minute_class(observed, zone=ZONE, walking_steps_threshold=threshold)


@pytest.mark.parametrize(
    ("steps", "heart_rate", "expected"),
    [
        (80, 100, WalkingMinuteClass.TRAINING),
        (80, 90, WalkingMinuteClass.TRAINING),
        (80, 110, WalkingMinuteClass.TRAINING),
        (61, 100, WalkingMinuteClass.TRAINING),
        (60, 100, None),
        (80, 115, WalkingMinuteClass.ABOVE_ZONE),
        (80, 85, WalkingMinuteClass.BELOW_ZONE),
        (80, None, WalkingMinuteClass.NO_HEART_RATE),
    ],
)
def test_вид_минуты_по_шагам_и_пульсу(
    steps: int, heart_rate: int | None, expected: WalkingMinuteClass | None
) -> None:
    assert _class(steps, heart_rate) is expected


def test_снятый_браслет_не_ходьба() -> None:
    assert _class(80, None, kind=ActivityKind.NOT_WORN) is None
    assert _class(80, None, kind=ActivityKind.CHARGING) is None


def test_порог_ходьбы_свой_у_пациента() -> None:
    assert _class(50, 100, threshold=40) is WalkingMinuteClass.TRAINING
