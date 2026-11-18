"""Приложение со всеми портами слайсов «Реабилитации» на фейках (пациент — `plans.PATIENT`)."""

from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

from kardiomonitor.app.main import create_app
from kardiomonitor.features.access.api import CurrentClinician
from kardiomonitor.features.access.domain.access_log import AccessLog
from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from kardiomonitor.features.rehab.domain.day_outcomes import DayOutcomes
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans
from kardiomonitor.shared_kernel import Clock, Transaction
from tests.fakes import FakeTransaction, FixedClock, replacing, settings_for_tests
from tests.features.access.clinicians import DOCTOR_A
from tests.features.access.fakes import FakeAccessLog
from tests.features.alerts.fakes import FakeAlertJournal
from tests.features.monitoring.fakes import FakeActivityHistory
from tests.features.patients.fakes import FakePatientRegistry
from tests.features.rehab.fakes import FakeDayOutcomes, FakeRehabPlans
from tests.features.rehab.plans import PATIENT, TODAY_NOON


@pytest.fixture
def outcomes() -> FakeDayOutcomes:
    return FakeDayOutcomes()


@pytest.fixture
def plans(outcomes: FakeDayOutcomes) -> FakeRehabPlans:
    return FakeRehabPlans(outcomes)


@pytest.fixture
def history() -> FakeActivityHistory:
    return FakeActivityHistory()


@pytest.fixture
def transaction() -> FakeTransaction:
    return FakeTransaction()


@pytest.fixture
def access_log() -> FakeAccessLog:
    return FakeAccessLog()


@pytest.fixture
async def client(
    plans: FakeRehabPlans,
    outcomes: FakeDayOutcomes,
    history: FakeActivityHistory,
    transaction: FakeTransaction,
    access_log: FakeAccessLog,
) -> AsyncIterator[AsyncClient]:
    registry = FakePatientRegistry()
    await registry.add(PATIENT, UploadToken.issue())
    app = create_app(
        settings_for_tests(),
        replacing(
            (PatientRegistry, registry),
            (AlertJournal, FakeAlertJournal()),
            (RehabPlans, plans),
            (DayOutcomes, outcomes),
            (ActivityHistory, history),
            (Clock, FixedClock(TODAY_NOON)),
            (Transaction, transaction),
            (CurrentClinician, CurrentClinician(DOCTOR_A)),
            (AccessLog, access_log),
        ),
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client
