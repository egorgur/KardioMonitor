"""Приложение со всеми портами слайсов «Оповещений» на фейках (`ports.Ports`)."""

from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

from kardiomonitor.app.main import create_app
from kardiomonitor.features.access.api import CurrentClinician
from kardiomonitor.features.access.domain.access_log import AccessLog
from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.live_heart_rate import LiveHeartRateLog
from kardiomonitor.features.patients.api import CurrentPatient
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from kardiomonitor.features.rehab.domain.day_outcomes import DayOutcomes
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans
from kardiomonitor.shared_kernel import Clock, Transaction
from tests.fakes import replacing, settings_for_tests
from tests.features.access.clinicians import DOCTOR_A
from tests.features.access.fakes import FakeAccessLog
from tests.features.alerts.ports import OTHER, Ports
from tests.features.monitoring.fakes import FakeLiveHeartRateLog
from tests.features.rehab.plans import PATIENT


@pytest.fixture
async def ports() -> Ports:
    ports = Ports()
    await ports.registry.add(PATIENT, UploadToken.issue())
    await ports.registry.add(OTHER, UploadToken.issue())
    return ports


@pytest.fixture
async def client(ports: Ports) -> AsyncIterator[AsyncClient]:
    app = create_app(
        settings_for_tests(),
        replacing(
            (AlertJournal, ports.journal),
            (ActivityHistory, ports.history),
            (LiveHeartRateLog, FakeLiveHeartRateLog()),
            (DayOutcomes, ports.outcomes),
            (RehabPlans, ports.plans),
            (PatientRegistry, ports.registry),
            (Clock, ports.clock),
            (Transaction, ports.transaction),
            (CurrentPatient, CurrentPatient(PATIENT)),
            (CurrentClinician, CurrentClinician(DOCTOR_A)),
            (AccessLog, FakeAccessLog()),
        ),
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client
