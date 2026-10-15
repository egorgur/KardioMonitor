"""Минута активности и её составляющие."""

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum

from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.shared_kernel import DomainRuleViolation

MAX_BAND_CODE = 255


@dataclass(frozen=True, slots=True)
class StepCount:
    """Шаги — неотрицательное целое."""

    value: int

    def __post_init__(self) -> None:
        if self.value < 0:
            raise DomainRuleViolation(f"шагов меньше нуля: {self.value}", code="negative_steps")


@dataclass(frozen=True, slots=True)
class Intensity:
    """Сырое значение браслета 0–255. Смысл не расшифрован — не интерпретировать."""

    value: int

    def __post_init__(self) -> None:
        if not 0 <= self.value <= MAX_BAND_CODE:
            raise DomainRuleViolation(
                f"интенсивность вне 0–{MAX_BAND_CODE}: {self.value}", code="intensity_out_of_range"
            )


class ActivityKind(StrEnum):
    """Тип активности за минуту. Коды браслета расшифровывает телефон."""

    NOT_WORN = "not_worn"
    CHARGING = "charging"
    SLEEP = "sleep"
    OTHER = "other"

    @property
    def is_worn(self) -> bool:
        """Снятый и заряжающийся браслет пульс и шаги не измеряет."""
        return self not in (ActivityKind.NOT_WORN, ActivityKind.CHARGING)


@dataclass(frozen=True, slots=True)
class ActivityMinute:
    """Запись браслета за одну минуту. Ключ — пациент и время начала минуты.

    `kind_code` — исходный код браслета для `other` (не расшифрован), иначе None.
    """

    started_at: datetime
    steps: StepCount
    heart_rate: HeartRate | None
    kind: ActivityKind
    kind_code: int | None
    intensity: Intensity

    def __post_init__(self) -> None:
        if self.started_at.tzinfo is None:
            raise DomainRuleViolation(
                f"время минуты без часового пояса: {self.started_at}", code="naive_datetime"
            )
        if self.started_at.second or self.started_at.microsecond:
            raise DomainRuleViolation(
                f"минута должна начинаться с 0 секунд: {self.started_at}", code="minute_not_aligned"
            )
        if self.kind_code is not None and not 0 <= self.kind_code <= MAX_BAND_CODE:
            raise DomainRuleViolation(
                f"код типа браслета вне 0–{MAX_BAND_CODE}: {self.kind_code}",
                code="kind_code_out_of_range",
            )

    @property
    def is_worn(self) -> bool:
        return self.kind.is_worn
