"""Вход врача по логину и паролю: блокировка, одинаковый отказ (ТР-11, п. 5)."""

import pytest

from kardiomonitor.features.access.domain.authentication import (
    Authentication,
    InvalidCredentialsError,
    SignInLockedError,
)
from tests.fakes import FixedClock
from tests.features.access.clinicians import DOCTOR_A, PASSWORD
from tests.features.access.fakes import (
    FakeClinicians,
    FakeClinicianSessions,
    FakePasswordHasher,
)


@pytest.fixture
def clinicians() -> FakeClinicians:
    return FakeClinicians(DOCTOR_A)


@pytest.fixture
def authentication(clinicians: FakeClinicians) -> Authentication:
    return Authentication(clinicians, FakePasswordHasher(), FakeClinicianSessions(), FixedClock())


async def test_верный_логин_и_пароль_это_врач(authentication: Authentication) -> None:
    assert (await authentication.authenticate(" Doctor.A ", PASSWORD)).id == DOCTOR_A.id


@pytest.mark.parametrize(
    ("login", "password"), [("doctor.a", "wrong"), ("nobody", PASSWORD), ("!", "x")]
)
async def test_неверный_логин_или_пароль_одинаковый_отказ(
    authentication: Authentication, login: str, password: str
) -> None:
    with pytest.raises(InvalidCredentialsError):
        await authentication.authenticate(login, password)


async def test_после_5_неудач_даже_верный_пароль_не_пускает(
    authentication: Authentication, clinicians: FakeClinicians
) -> None:
    for _ in range(5):
        with pytest.raises(InvalidCredentialsError):
            await authentication.authenticate("doctor.a", "wrong")

    with pytest.raises(SignInLockedError):
        await authentication.authenticate("doctor.a", PASSWORD)
    assert clinicians.clinicians[DOCTOR_A.id].locked_until is not None
