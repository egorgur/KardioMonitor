"""Дневная проверка (ТР-08): критерии приёмки — пороги 130 и 1 000 шагов, нормы 30 мин и
10 000 шагов, время оповещений 17:00 UTC, пациент в UTC+3 (Москва)."""

from dataclasses import replace
from datetime import UTC, date, datetime, time, timedelta

from kardiomonitor.features.alerts.domain.daily_check import DailyCheck
from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    DailyTargetResult,
    LowActivityResult,
)
from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.patients.domain.patient import Patient, PatientName
from kardiomonitor.features.rehab.domain.monitoring_settings import DEFAULT_MONITORING_SETTINGS
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from tests.features.monitoring.minutes import minute
from tests.features.rehab.plans import MOSCOW, PATIENT

DAY = date(2026, 10, 12)
AT_1700_UTC = datetime(2026, 10, 12, 17, 0, tzinfo=UTC)
PLAN = RehabPlan.default_for(PATIENT)


def _day(training: int, idle_steps: int) -> list[ActivityMinute]:
    """`training` тренировочных минут по 80 шагов и минута с `idle_steps` шагами без ходьбы."""
    start = datetime(2026, 10, 12, 9, tzinfo=MOSCOW)
    walk = [minute(i, steps=80, heart_rate=100, start=start) for i in range(training)]
    return [*walk, minute(training, steps=idle_steps, start=start)]


def _kinds_and_details(minutes: list[ActivityMinute]) -> dict[AlertKind, object]:
    check = DailyCheck.due(PATIENT, PLAN, now=AT_1700_UTC)
    assert check is not None
    return {alert.kind: alert.details for alert in check.alerts(PLAN, minutes, now=AT_1700_UTC)}


def test_в_1700_utc_оценивается_день_пациента_в_котором_у_него_2000() -> None:
    check = DailyCheck.due(PATIENT, PLAN, now=AT_1700_UTC + timedelta(seconds=30))

    assert check is not None
    assert check.day == DAY
    assert check.period == (datetime(2026, 10, 12, tzinfo=MOSCOW), AT_1700_UTC)


def test_12_минут_и_6200_шагов_норма_дня_не_выполнена() -> None:
    alerts = _kinds_and_details(_day(12, 6_200 - 12 * 80))

    assert alerts == {
        AlertKind.DAILY_TARGET_MISSED: DailyTargetResult(
            training_minutes=12, target_training_minutes=30, steps=6_200, target_steps=10_000
        )
    }


def test_35_минут_и_11000_шагов_норма_дня_выполнена() -> None:
    alerts = _kinds_and_details(_day(35, 11_000 - 35 * 80))

    assert set(alerts) == {AlertKind.DAILY_TARGET_MET}


def test_400_шагов_за_день_почти_не_ходит_и_норма_не_выполнена() -> None:
    alerts = _kinds_and_details(_day(0, 400))

    assert set(alerts) == {AlertKind.DAILY_TARGET_MISSED, AlertKind.LOW_ACTIVITY}
    assert alerts[AlertKind.LOW_ACTIVITY] == LowActivityResult(steps=400, threshold_steps=1_000)


def test_до_времени_дневных_оповещений_проверки_нет() -> None:
    assert DailyCheck.due(PATIENT, PLAN, now=AT_1700_UTC - timedelta(seconds=1)) is None


def test_после_перезапуска_сервера_проверка_дня_догоняется() -> None:
    check = DailyCheck.due(PATIENT, PLAN, now=datetime(2026, 10, 12, 23, 30, tzinfo=UTC))

    assert check is not None
    assert (check.moment, check.day) == (AT_1700_UTC, DAY)


def test_время_оповещений_2200_utc_оценивает_следующий_день_пациента_в_utc_плюс_3() -> None:
    late = replace(DEFAULT_MONITORING_SETTINGS, daily_alert_time_utc=time(22, 0))
    plan = PLAN.revised(PLAN.current.program, late, today=DAY)

    check = DailyCheck.due(PATIENT, plan, now=datetime(2026, 10, 12, 22, 0, tzinfo=UTC))

    assert check is not None
    assert check.day == date(2026, 10, 13), "22:00 UTC — уже 01:00 следующего дня у пациента"


def test_день_вне_программы_не_проверяется() -> None:
    after_program = datetime.combine(PLAN.current.program.last_date, time(17), tzinfo=UTC)

    assert DailyCheck.due(PATIENT, PLAN, now=after_program + timedelta(days=1)) is None


def test_пациент_зарегистрирован_после_времени_оповещений_в_этот_день_не_проверяется() -> None:
    registered = datetime(2026, 10, 12, 18, 0, tzinfo=UTC)
    newcomer = Patient.register(PatientName("Новый"), MOSCOW, now=registered)

    check = DailyCheck.due(newcomer, RehabPlan.default_for(newcomer), now=registered)

    assert check is None
