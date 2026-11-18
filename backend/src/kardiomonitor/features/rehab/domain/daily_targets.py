"""Нормы дня: зона тренировочного пульса, тренировочные минуты, шаги (ТР-01)."""

from dataclasses import dataclass

from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.shared_kernel import DomainRuleViolation

# Пределы — предположение В-02 (docs/requirements.md): ответ клиники меняет только их.
MIN_ZONE_BPM = 40
MAX_ZONE_BPM = 220
MAX_TRAINING_MINUTES = 24 * 60
MAX_DAILY_STEPS = 100_000


@dataclass(frozen=True, slots=True)
class TrainingHeartRateZone:
    """Диапазон тренировочного пульса, уд/мин; границы включительно."""

    lower_bpm: int
    upper_bpm: int

    def __post_init__(self) -> None:
        if self.lower_bpm >= self.upper_bpm:
            raise DomainRuleViolation(
                f"нижняя граница должна быть меньше верхней: {self.lower_bpm}–{self.upper_bpm}",
                code="invalid_training_zone",
            )
        if self.lower_bpm < MIN_ZONE_BPM or self.upper_bpm > MAX_ZONE_BPM:
            raise DomainRuleViolation(
                f"зона пульса должна быть в пределах {MIN_ZONE_BPM}–{MAX_ZONE_BPM}: "
                f"{self.lower_bpm}–{self.upper_bpm}",
                code="invalid_training_zone",
            )

    def contains(self, heart_rate: HeartRate) -> bool:
        return self.lower_bpm <= heart_rate.bpm <= self.upper_bpm


@dataclass(frozen=True, slots=True)
class DailyTargets:
    """Нормы одного дня программы."""

    training_zone: TrainingHeartRateZone
    training_minutes: int
    steps: int

    def __post_init__(self) -> None:
        if not 1 <= self.training_minutes <= MAX_TRAINING_MINUTES:
            raise DomainRuleViolation(
                f"тренировочных минут в день — от 1 до {MAX_TRAINING_MINUTES}: "
                f"{self.training_minutes}",
                code="invalid_daily_targets",
            )
        if not 0 <= self.steps <= MAX_DAILY_STEPS:
            raise DomainRuleViolation(
                f"шагов в день — от 0 до {MAX_DAILY_STEPS}: {self.steps}",
                code="invalid_daily_targets",
            )


DEFAULT_DAILY_TARGETS = DailyTargets(TrainingHeartRateZone(90, 110), 30, 10_000)
"""Нормы нового пациента (ТР-01, п. 5)."""
