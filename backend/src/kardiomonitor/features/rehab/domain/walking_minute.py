"""Минута ходьбы и тренировочная минута (ТР-02). Те же правила — в Android."""

from enum import StrEnum

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.rehab.domain.daily_targets import TrainingHeartRateZone


class WalkingMinuteClass(StrEnum):
    """Вид минуты ходьбы по пульсу."""

    TRAINING = "training"
    """Тренировочная минута: пульс в зоне."""
    ABOVE_ZONE = "above_zone"
    BELOW_ZONE = "below_zone"
    NO_HEART_RATE = "no_heart_rate"


def walking_minute_class(
    minute: ActivityMinute, *, zone: TrainingHeartRateZone, walking_steps_threshold: int
) -> WalkingMinuteClass | None:
    """None — не минута ходьбы: браслет не надет или шагов не больше порога.

    Ровно порог — не ходьба (ТР-02, п. 1).
    """
    if not minute.is_worn or minute.steps.value <= walking_steps_threshold:
        return None
    if minute.heart_rate is None:
        return WalkingMinuteClass.NO_HEART_RATE
    if zone.contains(minute.heart_rate):
        return WalkingMinuteClass.TRAINING
    if minute.heart_rate.bpm > zone.upper_bpm:
        return WalkingMinuteClass.ABOVE_ZONE
    return WalkingMinuteClass.BELOW_ZONE
