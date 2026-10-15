"""Пульс сейчас: значения непрерывного измерения, которые телефон шлёт раз в несколько секунд."""

from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.shared_kernel import DomainRuleViolation


@dataclass(frozen=True, slots=True)
class LiveHeartRateReading:
    """Одно значение «пульса сейчас» и момент замера."""

    measured_at: datetime
    heart_rate: HeartRate

    def __post_init__(self) -> None:
        if self.measured_at.tzinfo is None:
            raise DomainRuleViolation(
                f"время замера без часового пояса: {self.measured_at}", code="naive_datetime"
            )


class LiveHeartRateLog(Protocol):
    """Журнал «пульса сейчас». Повтор того же момента замера не дублирует запись."""

    async def record(self, patient_id: PatientId, reading: LiveHeartRateReading) -> None: ...

    async def readings_since(
        self, patient_id: PatientId, since: datetime
    ) -> list[LiveHeartRateReading]:
        """Замеры с момента `since`, по порядку."""
        ...

    async def latest_of(self, patient_id: PatientId) -> LiveHeartRateReading | None: ...
