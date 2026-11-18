"""Врач, пароль и сеанс — правила ТР-11, п. 5."""

from datetime import timedelta

import pytest

from kardiomonitor.features.access.domain.clinician import Login
from kardiomonitor.features.access.domain.clinician_session import ClinicianSession
from kardiomonitor.features.access.domain.password import Password
from kardiomonitor.shared_kernel import DomainRuleViolation
from tests.fakes import NOW
from tests.features.access.clinicians import DOCTOR_A


def test_пятая_неудача_подряд_закрывает_вход_на_15_минут() -> None:
    clinician = DOCTOR_A
    for _ in range(5):
        clinician = clinician.with_failed_sign_in(NOW)

    assert clinician.is_locked(NOW + timedelta(minutes=14))
    assert not clinician.is_locked(NOW + timedelta(minutes=15))


def test_четыре_неудачи_вход_не_закрывают() -> None:
    clinician = DOCTOR_A
    for _ in range(4):
        clinician = clinician.with_failed_sign_in(NOW)

    assert not clinician.is_locked(NOW)


def test_удачный_вход_обнуляет_счётчик_неудач() -> None:
    clinician = DOCTOR_A.with_failed_sign_in(NOW).with_successful_sign_in()

    assert clinician.failed_sign_ins == 0


def test_пароль_короче_12_знаков_не_принимается() -> None:
    with pytest.raises(DomainRuleViolation) as error:
        Password("short-pass1")

    assert error.value.code == "weak_password"


def test_пароль_и_хеш_не_попадают_в_repr() -> None:
    assert "secret-password" not in repr(Password("secret-password"))
    assert DOCTOR_A.password_hash not in repr(DOCTOR_A)


@pytest.mark.parametrize("login", ["ab", "Doctor", "врач", "doctor a"])
def test_логин_строчными_латинскими_буквами_от_3_знаков(login: str) -> None:
    with pytest.raises(DomainRuleViolation):
        Login(login)


def test_сеанс_истекает_через_8_часов_без_запросов() -> None:
    session = ClinicianSession.started(DOCTOR_A.id, NOW)

    assert not session.is_expired(NOW + timedelta(hours=7, minutes=59))
    assert session.is_expired(NOW + timedelta(hours=8))
