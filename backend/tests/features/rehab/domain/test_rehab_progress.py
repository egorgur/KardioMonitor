"""Прогресс реабилитации — ТР-05, критерии приёмки (программа 42 дня с 01.10)."""

from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.patients.domain.patient import Patient, PatientName
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan
from kardiomonitor.features.rehab.domain.rehab_progress import ProgramDayStatus, RehabProgress
from tests.features.monitoring.minutes import minute

TZ = ZoneInfo("Europe/Moscow")
PATIENT = Patient.register(PatientName("Пациент"), TZ, now=datetime(2026, 10, 1, 9, tzinfo=TZ))
PLAN = RehabPlan.default_for(PATIENT)  # 42 дня с 01.10: 30 тренировочных минут и 10 000 шагов


def _met_day(day: date) -> list[ActivityMinute]:
    """30 тренировочных минут по 80 шагов и остаток до 10 000 шагов — норма выполнена."""
    start = datetime.combine(day, datetime.min.time(), tzinfo=TZ) + timedelta(hours=10)
    walk = [minute(i, steps=80, heart_rate=100, start=start) for i in range(30)]
    return [*walk, minute(31, steps=10_000 - 30 * 80, start=start)]


def _not_met_day(day: date) -> list[ActivityMinute]:
    return _met_day(day)[:10]


def _progress(minutes: list[ActivityMinute], today: date) -> RehabProgress:
    return RehabProgress.of(PLAN, minutes, time_zone=TZ, today=today)


def test_день_12_из_42_норма_выполнена_в_9_днях_из_11() -> None:
    met = [m for day in range(1, 10) for m in _met_day(date(2026, 10, day))]
    not_met = _not_met_day(date(2026, 10, 10)) + _not_met_day(date(2026, 10, 11))

    progress = _progress(met + not_met, today=date(2026, 10, 12))

    assert (progress.program_day, progress.duration_days) == (12, 42)
    assert (progress.met_days, progress.completed_days) == (9, 11)


def test_сегодня_в_прошедших_днях_но_не_в_счёте_выполненных() -> None:
    progress = _progress(_met_day(date(2026, 10, 3)), today=date(2026, 10, 3))

    assert progress.days[-1].status is ProgramDayStatus.IN_PROGRESS
    assert progress.met_days == 0
    assert progress.completed_days == 2


def test_день_без_данных_помечен_и_норма_не_выполнена() -> None:
    progress = _progress([], today=date(2026, 10, 2))

    assert progress.days[0].status is ProgramDayStatus.NO_DATA
    assert progress.met_days == 0


def test_после_последнего_дня_программа_завершена() -> None:
    progress = _progress([], today=date(2026, 11, 20))

    assert progress.is_finished
    assert (progress.program_day, progress.completed_days) == (42, 42)
    assert progress.current_stage is None


def test_до_начала_программы_дня_программы_нет() -> None:
    progress = _progress([], today=date(2026, 9, 30))

    assert progress.program_day is None
    assert progress.days == ()


def test_текущий_этап_с_номером() -> None:
    progress = _progress([], today=date(2026, 10, 5))

    assert progress.current_stage is not None
    assert (progress.current_stage.number, progress.current_stage.stage.last_day) == (1, 42)


def test_минута_относится_к_дню_по_поясу_пациента() -> None:
    late_evening_utc = datetime(2026, 10, 1, 21, 30, tzinfo=UTC)  # 2 октября 00:30 в Москве

    progress = _progress([minute(0, steps=100, start=late_evening_utc)], today=date(2026, 10, 3))

    assert [day.status for day in progress.days] == [
        ProgramDayStatus.NO_DATA, ProgramDayStatus.NOT_MET, ProgramDayStatus.IN_PROGRESS,
    ]  # fmt: skip
