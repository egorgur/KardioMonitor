"""Карточка пациента и поиск — критерии приёмки ТР-07 и ТР-06. Данные вымышленные."""

from datetime import UTC, date, datetime
from zoneinfo import ZoneInfo

import pytest

from kardiomonitor.features.patients.domain.enrollment_code import EnrollmentCode
from kardiomonitor.features.patients.domain.patient import Patient, PatientName
from kardiomonitor.features.patients.domain.patient_profile import (
    BodyParameters,
    FullName,
    PatientProfile,
)
from kardiomonitor.shared_kernel import DomainRuleViolation
from tests.features.access.clinicians import DOCTOR_A

NOW = datetime(2026, 10, 16, 9, 30, tzinfo=UTC)
BODY = BodyParameters(176, 82.5, "вымышленный диагноз")


def _profile(birth: date = date(1961, 4, 12), name: FullName | None = None) -> PatientProfile:
    return PatientProfile(name or FullName("Тестов", "Пётр", "Иванович"), birth, BODY)


def test_созданный_врачом_пациент_назван_по_фио_и_закреплён_за_врачом() -> None:
    patient = Patient.admit(_profile(), ZoneInfo("Europe/Moscow"), DOCTOR_A.id, now=NOW)

    assert patient.name == PatientName("Тестов Пётр Иванович")
    assert patient.is_attended_by(DOCTOR_A.id)


def test_без_фамилии_ошибка_укажите_фамилию() -> None:
    with pytest.raises(DomainRuleViolation, match="укажите фамилию") as error:
        FullName("", "Пётр")

    assert error.value.code == "invalid_full_name"


def test_отчество_необязательно() -> None:
    assert FullName("Тестов", "Пётр").display == "Тестов Пётр"


@pytest.mark.parametrize("birth", [date(2026, 10, 17), date(2010, 1, 1), date(1900, 1, 1)])
def test_дата_рождения_не_в_будущем_и_возраст_18_120(birth: date) -> None:
    with pytest.raises(DomainRuleViolation) as error:
        Patient.admit(_profile(birth), ZoneInfo("UTC"), DOCTOR_A.id, now=NOW)

    assert error.value.code == "invalid_birth_date"


def test_возраст_в_полных_годах() -> None:
    assert _profile(date(1961, 10, 17)).age_on(date(2026, 10, 16)) == 64
    assert _profile(date(1961, 10, 16)).age_on(date(2026, 10, 16)) == 65


@pytest.mark.parametrize(
    ("height", "weight", "diagnosis"), [(99, 80, "д"), (180, 301, "д"), (180, 80, " ")]
)
def test_биопараметры_в_допустимых_пределах(height: int, weight: float, diagnosis: str) -> None:
    with pytest.raises(DomainRuleViolation) as error:
        BodyParameters(height, weight, diagnosis)

    assert error.value.code == "invalid_body_parameters"


def test_карточка_не_раскрывает_данные_в_repr() -> None:
    text = repr(_profile())

    assert not any(secret in text for secret in ("Тестов", "1961", "диагноз", "176"))
    assert EnrollmentCode("secret-code").value not in repr(EnrollmentCode("secret-code"))


@pytest.mark.parametrize("query", ["иван", "ИВАН", " Иван "])
def test_поиск_по_подстроке_в_любой_части_фио(query: str) -> None:
    names = ["Иванов Пётр", "Иванова Анна", "Петров Иван", "Сидоров Олег"]

    assert [n for n in names if PatientName(n).matches(query)] == names[:3]


def test_поиск_не_различает_е_и_ё() -> None:
    assert PatientName("Семёнов Олег").matches("семенов")
    assert PatientName("Семенов Олег").matches("Семён")
