"""План реабилитации: версии программы и нормы прошлых дней — ТР-01, п. 8; ADR-0010."""

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from kardiomonitor.features.patients.domain.patient import Patient, PatientName
from kardiomonitor.features.rehab.domain.monitoring_settings import (
    DEFAULT_MONITORING_SETTINGS,
    MonitoringSettings,
)
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from kardiomonitor.features.rehab.domain.rehab_program import RehabProgram
from kardiomonitor.shared_kernel import DomainRuleViolation
from tests.features.rehab.plans import START, program, targets

PATIENT = Patient.register(
    PatientName("Пациент"),
    ZoneInfo("Asia/Novosibirsk"),
    now=datetime(2026, 9, 30, 20, 0, tzinfo=UTC),  # 1 октября 03:00 в Новосибирске
)


def _day(number: int) -> date:
    return START + timedelta(days=number - 1)


def test_программа_по_умолчанию_начинается_в_день_регистрации_по_поясу_пациента() -> None:
    plan = RehabPlan.default_for(PATIENT)

    assert plan.current.program == RehabProgram.default(date(2026, 10, 1))
    assert plan.current.settings == DEFAULT_MONITORING_SETTINGS


def test_порог_ходьбы_по_умолчанию_60_шагов_в_минуту() -> None:
    assert DEFAULT_MONITORING_SETTINGS.walking_steps_threshold == 60


def test_правка_в_день_10_меняет_нормы_с_дня_10_а_дни_1_9_прежние() -> None:
    before = RehabPlan.default_for(PATIENT).revised(program(), DEFAULT_MONITORING_SETTINGS, START)
    stricter = program((1, 15, targets(40)), (16, 25, targets(40)))

    plan = before.revised(stricter, DEFAULT_MONITORING_SETTINGS, today=_day(10))

    assert plan.targets_on(_day(9)) == targets(30)
    assert plan.targets_on(_day(10)) == targets(40)
    assert plan.targets_on(_day(15)) == targets(40)


def test_вторая_правка_за_день_заменяет_первую() -> None:
    plan = RehabPlan.default_for(PATIENT)

    plan = plan.revised(program(), DEFAULT_MONITORING_SETTINGS, _day(5))
    plan = plan.revised(
        program((1, 30, targets(45)), days=30), DEFAULT_MONITORING_SETTINGS, _day(5)
    )

    assert [revision.effective_from for revision in plan.revisions] == [START, _day(5)]
    assert plan.targets_on(_day(5)) == targets(45)


def test_параметры_наблюдения_прошлого_дня_берутся_из_его_версии() -> None:
    stricter = MonitoringSettings(40, 130, 1_000, time(17))
    plan = RehabPlan.default_for(PATIENT).revised(program(), stricter, _day(10))

    assert plan.revision_on(_day(9)).settings == DEFAULT_MONITORING_SETTINGS
    assert plan.revision_on(_day(10)).settings == stricter


def test_день_вне_программы_своей_версии_оценивается_текущей() -> None:
    plan = RehabPlan.default_for(PATIENT)
    earlier_start = program((1, 30, targets(20)), days=30)
    moved = RehabProgram(START - timedelta(days=5), 30, earlier_start.stages)

    plan = plan.revised(moved, DEFAULT_MONITORING_SETTINGS, _day(3))

    assert plan.targets_on(START - timedelta(days=2)) == targets(20)


def test_план_без_версий_создать_нельзя() -> None:
    with pytest.raises(DomainRuleViolation):
        RehabPlan(PATIENT.id, ())


@pytest.mark.parametrize(
    "settings",
    [(0, 130, 1_000), (251, 130, 1_000), (60, 59, 1_000), (60, 251, 1_000), (60, 130, -1)],
)
def test_параметры_наблюдения_в_допустимых_пределах(settings: tuple[int, int, int]) -> None:
    with pytest.raises(DomainRuleViolation) as error:
        MonitoringSettings(*settings, daily_alert_time_utc=time(17))

    assert error.value.code == "invalid_monitoring_settings"
