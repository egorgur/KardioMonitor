"""Пациенты и фейки тестов слайсов «Оповещений»: телефон — пациент `plans.PATIENT`, панель —
его врач А; `OTHER` — пациент врача Б."""

from dataclasses import dataclass, field
from datetime import datetime

from kardiomonitor.features.patients.domain.patient import Patient, PatientName
from tests.fakes import FakeTransaction, FixedClock
from tests.features.access.clinicians import DOCTOR_B
from tests.features.alerts.fakes import FakeAlertJournal
from tests.features.monitoring.fakes import FakeActivityHistory
from tests.features.patients.fakes import FakePatientRegistry
from tests.features.rehab.fakes import FakeDayOutcomes, FakeRehabPlans
from tests.features.rehab.plans import MOSCOW, TODAY_NOON

OTHER = Patient.register(
    PatientName("Чужой"), MOSCOW, now=datetime(2026, 10, 1, 9, tzinfo=MOSCOW)
).with_attending_clinician(DOCTOR_B.id)


@dataclass
class Ports:
    """Фейки, которые видит тест."""

    journal: FakeAlertJournal = field(default_factory=FakeAlertJournal)
    history: FakeActivityHistory = field(default_factory=FakeActivityHistory)
    outcomes: FakeDayOutcomes = field(default_factory=FakeDayOutcomes)
    registry: FakePatientRegistry = field(default_factory=FakePatientRegistry)
    clock: FixedClock = field(default_factory=lambda: FixedClock(TODAY_NOON))
    transaction: FakeTransaction = field(default_factory=FakeTransaction)
    plans: FakeRehabPlans = field(init=False)

    def __post_init__(self) -> None:
        self.plans = FakeRehabPlans(self.outcomes)
