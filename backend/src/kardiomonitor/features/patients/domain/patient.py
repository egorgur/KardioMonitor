"""Пациент — корень агрегата контекста «Пациенты»."""

import uuid
from dataclasses import dataclass
from datetime import datetime
from typing import NewType, Self
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from kardiomonitor.shared_kernel import DomainRuleViolation

PatientId = NewType("PatientId", UUID)
"""Идентификатор пациента. Выдаётся сервером, не связан с ФИО и номером карты."""

MAX_NAME_LENGTH = 100


@dataclass(frozen=True, slots=True)
class PatientName:
    """Как показывать пациента на демонстрации. Непустое, без пробелов по краям, до 100 знаков."""

    value: str

    def __post_init__(self) -> None:
        if not self.value or self.value != self.value.strip():
            raise DomainRuleViolation("имя пациента пустое", code="invalid_patient_name")
        if len(self.value) > MAX_NAME_LENGTH:
            raise DomainRuleViolation(
                f"имя пациента длиннее {MAX_NAME_LENGTH} знаков", code="invalid_patient_name"
            )


def time_zone_of(name: str) -> ZoneInfo:
    """Часовой пояс пациента по IANA-имени (`Asia/Novosibirsk`): определяет границы его дня."""
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise DomainRuleViolation(
            f"неизвестный часовой пояс: {name}", code="unknown_time_zone"
        ) from error


@dataclass(frozen=True, slots=True)
class Patient:
    """Человек под наблюдением."""

    id: PatientId
    name: PatientName
    time_zone: ZoneInfo
    registered_at: datetime

    @classmethod
    def register(cls, name: PatientName, time_zone: ZoneInfo, *, now: datetime) -> Self:
        """Новый пациент с новым идентификатором."""
        if now.tzinfo is None:
            raise DomainRuleViolation("время без часового пояса", code="naive_datetime")
        return cls(PatientId(uuid.uuid4()), name, time_zone, now)
