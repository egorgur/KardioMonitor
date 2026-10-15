"""Пакет минут из одного запроса телефона."""

from dataclasses import dataclass
from datetime import datetime

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.shared_kernel import DomainRuleViolation

MAX_BATCH_MINUTES = 7 * 24 * 60
"""7 суток — окно первой синхронизации телефона (android/docs/domain-model.md, HistorySyncStart)."""


@dataclass(frozen=True, slots=True)
class MinuteBatch:
    """Не пустой, не больше 7 суток, без повторов времени начала."""

    minutes: tuple[ActivityMinute, ...]

    def __post_init__(self) -> None:
        if not self.minutes:
            raise DomainRuleViolation("пакет минут пуст", code="empty_minute_batch")
        if len(self.minutes) > MAX_BATCH_MINUTES:
            raise DomainRuleViolation(
                f"в пакете {len(self.minutes)} минут, допустимо не больше {MAX_BATCH_MINUTES}",
                code="minute_batch_too_large",
            )
        if len({minute.started_at for minute in self.minutes}) != len(self.minutes):
            raise DomainRuleViolation(
                "в пакете повторяется время начала минуты", code="duplicate_minute"
            )

    @property
    def last_started_at(self) -> datetime:
        return max(minute.started_at for minute in self.minutes)
