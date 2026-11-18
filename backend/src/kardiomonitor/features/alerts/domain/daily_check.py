"""Дневная проверка: норма дня и «почти не ходит» во время дневных оповещений (ТР-08, п. 1–4)."""

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime
from typing import Self

from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    DailyTargetResult,
    LowActivityResult,
    PatientAlert,
)
from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.monitoring.domain.patient_day import PatientDay
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan


@dataclass(frozen=True, slots=True)
class DailyCheck:
    """Проверка, которая наступила: `moment` — сегодня по UTC во время дневных оповещений,
    `day` — день пациента в его поясе, в который попадает этот момент (17:00 UTC = 20:00 в
    UTC+3 — оценивается этот день). Минуты дня — с его начала до момента проверки.
    """

    patient: Patient
    moment: datetime
    day: date

    @classmethod
    def due(cls, patient: Patient, plan: RehabPlan, *, now: datetime) -> Self | None:
        """Проверка сегодняшнего дня UTC, если её время прошло; None — ещё рано или оценивать
        нечего.

        После перезапуска сервера проверка догоняет пропущенную — только текущего дня UTC.
        Не оцениваются: момент раньше регистрации пациента (дня наблюдения ещё не было) и день
        вне программы (ТР-08, п. 7).
        """
        alert_time = plan.current.settings.daily_alert_time_utc
        moment = datetime.combine(now.astimezone(UTC).date(), alert_time, tzinfo=UTC)
        day = patient.day_of(moment)
        if now < moment or moment < patient.registered_at or plan.targets_on(day) is None:
            return None
        return cls(patient, moment, day)

    @property
    def period(self) -> tuple[datetime, datetime]:
        """Какие минуты учитывать: [начало дня пациента, момент проверки)."""
        return PatientDay(self.day, self.patient.time_zone).start, self.moment

    def alerts(
        self, plan: RehabPlan, minutes: Sequence[ActivityMinute], *, now: datetime
    ) -> tuple[PatientAlert, ...]:
        """Оповещения дня по данным на момент проверки; день вне программы — никаких (п. 7).

        Всегда одно из «норма выполнена» / «не выполнена»; «почти не ходит» — вместе с ними
        (п. 4, В-16). Нормы и пороги — версии плана, действовавшей в этот день.
        """
        progress = plan.progress_on(self.day, minutes, is_final=False)
        if progress is None:
            return ()
        patient_id = self.patient.id
        result = DailyTargetResult(
            training_minutes=progress.training_minutes,
            target_training_minutes=progress.targets.training_minutes,
            steps=progress.steps,
            target_steps=progress.targets.steps,
        )
        kind = AlertKind.DAILY_TARGET_MET if progress.is_met else AlertKind.DAILY_TARGET_MISSED
        alerts = [PatientAlert.daily(patient_id, kind, self.day, result, now=now)]
        threshold = plan.revision_on(self.day).settings.low_activity_steps
        if progress.steps < threshold:
            low_activity = LowActivityResult(steps=progress.steps, threshold_steps=threshold)
            alerts.append(
                PatientAlert.daily(
                    patient_id, AlertKind.LOW_ACTIVITY, self.day, low_activity, now=now
                )
            )
        return tuple(alerts)
