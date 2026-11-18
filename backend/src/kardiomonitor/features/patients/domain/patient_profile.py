"""Карточка пациента: ФИО, дата рождения, биопараметры (ТР-07). Особая категория персональных
данных — не в журналы, только лечащему врачу; `repr` значения не раскрывает."""

from dataclasses import dataclass, field
from datetime import date
from typing import NoReturn

from kardiomonitor.shared_kernel import DomainRuleViolation

# Пределы — предположения ТР-07 (docs/requirements.md); ответ клиники меняет только их.
MAX_NAME_PART_LENGTH = 32
"""Часть ФИО; три части через пробел помещаются в `PatientName` (100 знаков)."""
MIN_AGE_YEARS = 18
MAX_AGE_YEARS = 120
MIN_HEIGHT_CM, MAX_HEIGHT_CM = 100, 250
MIN_WEIGHT_KG, MAX_WEIGHT_KG = 30, 300
MAX_DIAGNOSIS_LENGTH = 1000


@dataclass(frozen=True, slots=True)
class FullName:
    """Фамилия и имя обязательны, отчество — если есть; части без пробелов по краям."""

    last_name: str = field(repr=False)
    first_name: str = field(repr=False)
    middle_name: str | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        _check_part(self.last_name, "укажите фамилию")
        _check_part(self.first_name, "укажите имя")
        if self.middle_name is not None:
            _check_part(self.middle_name, "отчество пустое")

    @property
    def display(self) -> str:
        """«Фамилия Имя Отчество» — так пациент виден в списке и ищется (ТР-06)."""
        return " ".join(
            part for part in (self.last_name, self.first_name, self.middle_name) if part
        )


def _check_part(part: str, empty_message: str) -> None:
    if not part or part != part.strip():
        raise DomainRuleViolation(empty_message, code="invalid_full_name")
    if len(part) > MAX_NAME_PART_LENGTH:
        raise DomainRuleViolation(
            f"часть ФИО длиннее {MAX_NAME_PART_LENGTH} знаков", code="invalid_full_name"
        )


@dataclass(frozen=True, slots=True)
class BodyParameters:
    """Рост, вес, диагноз. Список дополняется (В-06): новый параметр — новое поле и миграция."""

    height_cm: int = field(repr=False)
    weight_kg: float = field(repr=False)
    diagnosis: str = field(repr=False)

    def __post_init__(self) -> None:
        if not MIN_HEIGHT_CM <= self.height_cm <= MAX_HEIGHT_CM:
            _body_error(f"рост — от {MIN_HEIGHT_CM} до {MAX_HEIGHT_CM} см")
        if not MIN_WEIGHT_KG <= self.weight_kg <= MAX_WEIGHT_KG:
            _body_error(f"вес — от {MIN_WEIGHT_KG} до {MAX_WEIGHT_KG} кг")
        if not self.diagnosis.strip() or len(self.diagnosis) > MAX_DIAGNOSIS_LENGTH:
            _body_error(f"диагноз — от 1 до {MAX_DIAGNOSIS_LENGTH} знаков")


def _body_error(message: str) -> NoReturn:
    raise DomainRuleViolation(message, code="invalid_body_parameters")


@dataclass(frozen=True, slots=True)
class PatientProfile:
    """Карточка, которую заполняет клиника при создании пациента."""

    full_name: FullName
    birth_date: date = field(repr=False)
    body: BodyParameters

    def age_on(self, day: date) -> int:
        """Полных лет на день `day`."""
        had_birthday = (day.month, day.day) >= (self.birth_date.month, self.birth_date.day)
        return day.year - self.birth_date.year - (0 if had_birthday else 1)

    def check_birth_date(self, today: date) -> None:
        """Дата рождения не в будущем, возраст 18–120 лет (ТР-07, п. 1)."""
        if self.birth_date > today:
            raise DomainRuleViolation("дата рождения в будущем", code="invalid_birth_date")
        if not MIN_AGE_YEARS <= self.age_on(today) <= MAX_AGE_YEARS:
            raise DomainRuleViolation(
                f"возраст — от {MIN_AGE_YEARS} до {MAX_AGE_YEARS} лет", code="invalid_birth_date"
            )
