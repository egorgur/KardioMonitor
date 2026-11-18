"""Параметры наблюдения пациента: пороги и время дневных оповещений (ТР-01, п. 4)."""

from dataclasses import dataclass
from datetime import time
from typing import NoReturn

from kardiomonitor.features.rehab.domain.daily_targets import MAX_DAILY_STEPS, MAX_TRAINING_MINUTES
from kardiomonitor.shared_kernel import DomainRuleViolation

# Пределы — предположение В-02 (docs/requirements.md).
MAX_WALKING_STEPS_THRESHOLD = 250
MIN_HIGH_HEART_RATE_THRESHOLD = 60
MAX_HIGH_HEART_RATE_THRESHOLD = 250


@dataclass(frozen=True, slots=True)
class MonitoringSettings:
    """Настройки пациента на всю программу.

    `walking_steps_threshold` — шагов за минуту, **больше** которых минута — минута ходьбы (ТР-02).
    `high_heart_rate_threshold` — пульс выше — оповещение «высокий пульс» (ТР-08).
    `low_activity_steps` — шагов за день меньше — оповещение «почти не ходит» (ТР-08).
    `daily_alert_time_utc` — время дневных оповещений, UTC (ТР-08).
    """

    walking_steps_threshold: int
    high_heart_rate_threshold: int
    low_activity_steps: int
    daily_alert_time_utc: time
    training_minutes_upper_threshold: int | None = None

    def __post_init__(self) -> None:
        if not 1 <= self.walking_steps_threshold <= MAX_WALKING_STEPS_THRESHOLD:
            _settings_error(
                f"порог ходьбы — от 1 до {MAX_WALKING_STEPS_THRESHOLD} шагов в минуту: "
                f"{self.walking_steps_threshold}"
            )
        high = self.high_heart_rate_threshold
        if not MIN_HIGH_HEART_RATE_THRESHOLD <= high <= MAX_HIGH_HEART_RATE_THRESHOLD:
            _settings_error(
                f"порог высокого пульса — от {MIN_HIGH_HEART_RATE_THRESHOLD} до "
                f"{MAX_HIGH_HEART_RATE_THRESHOLD} уд/мин: {high}"
            )
        if not 0 <= self.low_activity_steps <= MAX_DAILY_STEPS:
            _settings_error(
                f"порог «почти не ходит» — от 0 до {MAX_DAILY_STEPS} шагов: "
                f"{self.low_activity_steps}"
            )
        threshold = self.training_minutes_upper_threshold
        if threshold is not None and (
            type(threshold) is not int or not 1 <= threshold <= MAX_TRAINING_MINUTES
        ):
            _settings_error(f"верхний порог тренировочных минут — от 1 до {MAX_TRAINING_MINUTES}")
        if self.daily_alert_time_utc.tzinfo is not None:
            _settings_error("время дневных оповещений задаётся в UTC без пояса")


def _settings_error(message: str) -> NoReturn:
    raise DomainRuleViolation(message, code="invalid_monitoring_settings")


DEFAULT_MONITORING_SETTINGS = MonitoringSettings(
    walking_steps_threshold=60,  # решено клиникой 2026-10-29
    # Пример из критериев ТР-08, не медицинская рекомендация: до выпуска к пациентам — ответ
    # клиники (В-21).
    high_heart_rate_threshold=130,
    low_activity_steps=1_000,
    daily_alert_time_utc=time(17, 0),
)
