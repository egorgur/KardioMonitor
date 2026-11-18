"""Оповещение — запись о событии у пациента; видят врач и пациент (ТР-08).

Не путать с напоминанием (ТР-10): напоминания настраивает пациент на телефоне, сервер их не
хранит. Тексты оповещений сервер не формирует — только значения: тексты пациенту утверждает
врач (В-19), их собирают панель и телефон.
"""

import uuid
from dataclasses import dataclass, replace
from datetime import date, datetime
from enum import StrEnum
from typing import NewType, Self
from uuid import UUID

from kardiomonitor.features.alerts.domain.high_heart_rate_episode import HighHeartRateEpisode
from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.shared_kernel import DomainRuleViolation

AlertId = NewType("AlertId", UUID)


class AlertKind(StrEnum):
    HIGH_HEART_RATE = "high_heart_rate"
    DAILY_TARGET_MISSED = "daily_target_missed"
    DAILY_TARGET_MET = "daily_target_met"
    LOW_ACTIVITY = "low_activity"
    TRAINING_MINUTES_EXCEEDED = "training_minutes_exceeded"

    @property
    def is_scheduled(self) -> bool:
        """Виды, которые создаёт только плановая дневная проверка."""
        return self in (
            AlertKind.DAILY_TARGET_MISSED,
            AlertKind.DAILY_TARGET_MET,
            AlertKind.LOW_ACTIVITY,
        )

    @property
    def is_daily(self) -> bool:
        """Имеет календарный день пациента (не обязательно создаётся планировщиком)."""
        return self is not AlertKind.HIGH_HEART_RATE


@dataclass(frozen=True, slots=True)
class DailyTargetResult:
    """Выполнение нормы дня на момент проверки: «12 из 30 мин, 6 200 из 10 000 шагов»."""

    training_minutes: int
    target_training_minutes: int
    steps: int
    target_steps: int


@dataclass(frozen=True, slots=True)
class LowActivityResult:
    """Шагов за день на момент проверки и порог «почти не ходит»."""

    steps: int
    threshold_steps: int


@dataclass(frozen=True, slots=True)
class TrainingMinutesExceeded:
    """Неизменяемый снимок первого превышения верхнего порога за день пациента."""

    training_minutes: int
    threshold_minutes: int


type AlertDetails = (
    HighHeartRateEpisode | DailyTargetResult | LowActivityResult | TrainingMinutesExceeded
)

_DETAILS_OF_KIND: dict[AlertKind, type] = {
    AlertKind.HIGH_HEART_RATE: HighHeartRateEpisode,
    AlertKind.DAILY_TARGET_MISSED: DailyTargetResult,
    AlertKind.DAILY_TARGET_MET: DailyTargetResult,
    AlertKind.LOW_ACTIVITY: LowActivityResult,
    AlertKind.TRAINING_MINUTES_EXCEEDED: TrainingMinutesExceeded,
}


@dataclass(frozen=True, slots=True)
class PatientAlert:
    """Оповещение пациента. Не удаляется (ТР-08, п. 9).

    `day` — оцениваемый день пациента, в том числе у превышения минут; у «высокого пульса» — None,
    момент
    события — начало эпизода. `viewed_at` — когда врач отметил «просмотрено».
    """

    id: AlertId
    patient_id: PatientId
    kind: AlertKind
    day: date | None
    details: AlertDetails
    created_at: datetime
    viewed_at: datetime | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.details, _DETAILS_OF_KIND[self.kind]):
            raise TypeError(f"значения оповещения не того вида: {self.kind}")
        if self.kind.is_daily != (self.day is not None):
            raise ValueError("день — только у дневного оповещения, и у него обязательно")
        if self.created_at.tzinfo is None:
            raise DomainRuleViolation("время создания без часового пояса", code="naive_datetime")

    @classmethod
    def high_heart_rate(
        cls, patient_id: PatientId, episode: HighHeartRateEpisode, *, now: datetime
    ) -> Self:
        return cls(_new_id(), patient_id, AlertKind.HIGH_HEART_RATE, None, episode, now)

    @classmethod
    def daily(
        cls,
        patient_id: PatientId,
        kind: AlertKind,
        day: date,
        details: DailyTargetResult | LowActivityResult | TrainingMinutesExceeded,
        *,
        now: datetime,
    ) -> Self:
        return cls(_new_id(), patient_id, kind, day, details, now)

    @property
    def is_viewed(self) -> bool:
        return self.viewed_at is not None

    @property
    def episode(self) -> HighHeartRateEpisode | None:
        return self.details if isinstance(self.details, HighHeartRateEpisode) else None

    def with_episode(self, episode: HighHeartRateEpisode) -> Self:
        """Эпизод продлился: новые значения и снова «не просмотрено» — врач увидит изменение."""
        if self.kind is not AlertKind.HIGH_HEART_RATE:
            raise ValueError("эпизод есть только у оповещения «высокий пульс»")
        return replace(self, details=episode, viewed_at=None)


def _new_id() -> AlertId:
    return AlertId(uuid.uuid4())
