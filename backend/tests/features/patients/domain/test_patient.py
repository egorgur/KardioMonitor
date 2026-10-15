"""Правила пациента и токена загрузки."""

import pytest

from kardiomonitor.features.patients.domain.patient import (
    Patient,
    PatientName,
    time_zone_of,
)
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from kardiomonitor.shared_kernel import DomainRuleViolation
from tests.fakes import NOW


@pytest.mark.parametrize("name", ["", "   ", " Иван", "x" * 101])
def test_имя_пациента_непустое_без_пробелов_по_краям_и_не_длиннее_100_знаков(name: str) -> None:
    with pytest.raises(DomainRuleViolation):
        PatientName(name)


def test_неизвестный_часовой_пояс_не_принимается() -> None:
    with pytest.raises(DomainRuleViolation) as error:
        time_zone_of("Mars/Olympus")

    assert error.value.code == "unknown_time_zone"


def test_новые_пациенты_получают_разные_идентификаторы() -> None:
    name, zone = PatientName("Иван П."), time_zone_of("Asia/Novosibirsk")

    first = Patient.register(name, zone, now=NOW)
    second = Patient.register(name, zone, now=NOW)

    assert first.id != second.id


def test_токен_не_раскрывается_в_repr() -> None:
    token = UploadToken.issue()

    assert token.value not in repr(token)


def test_по_отпечатку_нельзя_узнать_токен_но_он_одинаков_для_одного_токена() -> None:
    token = UploadToken.issue()

    assert token.fingerprint == UploadToken(token.value).fingerprint
    assert token.value not in token.fingerprint
