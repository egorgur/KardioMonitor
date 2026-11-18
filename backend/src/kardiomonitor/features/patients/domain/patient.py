"""Пациент — корень агрегата контекста «Пациенты»."""

import uuid
from dataclasses import dataclass, replace
from datetime import date, datetime
from typing import NewType, Self
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from kardiomonitor.features.access.domain.clinician import ClinicianId
from kardiomonitor.features.patients.domain.patient_profile import PatientProfile
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

    def matches(self, query: str) -> bool:
        """Поиск на главной (ТР-06): подстрока без учёта регистра и разницы `ё`/`е`."""
        return _searchable(query.strip()) in _searchable(self.value)


def _searchable(text: str) -> str:
    return text.casefold().replace("ё", "е")


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
    """Человек под наблюдением.

    `attending_clinician_id` — лечащий врач: только он видит пациента на панели (ТР-11). None —
    врач не назначен (регистрация с телефона по имени): пациента не видит никто, кроме
    администратора сервера.
    """

    id: PatientId
    name: PatientName
    time_zone: ZoneInfo
    registered_at: datetime
    attending_clinician_id: ClinicianId | None = None

    @classmethod
    def register(cls, name: PatientName, time_zone: ZoneInfo, *, now: datetime) -> Self:
        """Новый пациент с новым идентификатором."""
        if now.tzinfo is None:
            raise DomainRuleViolation("время без часового пояса", code="naive_datetime")
        return cls(PatientId(uuid.uuid4()), name, time_zone, now)

    @classmethod
    def admit(
        cls,
        profile: PatientProfile,
        time_zone: ZoneInfo,
        clinician_id: ClinicianId,
        *,
        now: datetime,
    ) -> Self:
        """Пациент, которого создал врач (ТР-07): имя — ФИО, создавший врач — лечащий."""
        patient = cls.register(PatientName(profile.full_name.display), time_zone, now=now)
        profile.check_birth_date(patient.day_of(now))
        return replace(patient, attending_clinician_id=clinician_id)

    def day_of(self, moment: datetime) -> date:
        """Календарный день пациента — по его часовому поясу, — в который попадает момент."""
        return moment.astimezone(self.time_zone).date()

    def is_attended_by(self, clinician_id: ClinicianId) -> bool:
        return self.attending_clinician_id == clinician_id

    def with_attending_clinician(self, clinician_id: ClinicianId) -> Self:
        """Назначить лечащего врача — прежний перестаёт видеть пациента (ТР-11, п. 4)."""
        return replace(self, attending_clinician_id=clinician_id)
