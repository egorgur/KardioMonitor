"""Построители программ и планов реабилитации для тестов."""

from datetime import date, datetime
from zoneinfo import ZoneInfo

from kardiomonitor.features.patients.domain.patient import Patient, PatientName
from kardiomonitor.features.rehab.domain.daily_targets import DailyTargets, TrainingHeartRateZone
from kardiomonitor.features.rehab.domain.rehab_program import RehabProgram, RehabStage
from tests.features.access.clinicians import DOCTOR_A

START = date(2026, 10, 1)
MOSCOW = ZoneInfo("Europe/Moscow")
PATIENT = Patient.register(
    PatientName("Пациент"), MOSCOW, now=datetime(2026, 10, 1, 9, tzinfo=MOSCOW)
).with_attending_clinician(DOCTOR_A.id)
TODAY_NOON = datetime(2026, 10, 12, 12, tzinfo=MOSCOW)
"""«Сейчас» в тестах слайсов: день 12 программы по умолчанию пациента `PATIENT` врача А."""


def targets(
    minutes: int = 30, steps: int = 10_000, zone: tuple[int, int] = (90, 110)
) -> DailyTargets:
    return DailyTargets(TrainingHeartRateZone(*zone), training_minutes=minutes, steps=steps)


def program(*stages: tuple[int, int, DailyTargets], days: int = 25) -> RehabProgram:
    """Программа с этапами (первый день, последний день, нормы); по умолчанию 1–15 и 16–25."""
    stages = stages or ((1, 15, targets(30)), (16, 25, targets(40)))
    return RehabProgram(START, days, tuple(RehabStage(*stage) for stage in stages))
