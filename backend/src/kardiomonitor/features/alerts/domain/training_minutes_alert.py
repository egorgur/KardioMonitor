"""Превышение индивидуального верхнего порога по полному итогу дня пациента."""

from collections.abc import Sequence
from datetime import date, datetime

from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    PatientAlert,
    TrainingMinutesExceeded,
)
from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan


def training_minutes_alert(
    patient: Patient,
    plan: RehabPlan,
    day: date,
    minutes: Sequence[ActivityMinute],
    *,
    now: datetime,
) -> PatientAlert | None:
    """Равенство допустимо; вне программы своей версии и без порога проверки нет."""
    revision = plan.revision_on(day)
    threshold = revision.settings.training_minutes_upper_threshold
    if threshold is None or revision.program.targets_on(day) is None:
        return None
    progress = plan.progress_on(day, minutes, is_final=day < patient.day_of(now))
    if progress is None or progress.training_minutes <= threshold:
        return None
    return PatientAlert.daily(
        patient.id,
        AlertKind.TRAINING_MINUTES_EXCEEDED,
        day,
        TrainingMinutesExceeded(progress.training_minutes, threshold),
        now=now,
    )
