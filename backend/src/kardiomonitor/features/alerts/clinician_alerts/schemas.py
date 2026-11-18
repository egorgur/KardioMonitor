"""Контракт API слайса «Оповещения врачу» (api-contract.md)."""

from datetime import date
from typing import Self
from uuid import UUID

from kardiomonitor.features.alerts.domain.high_heart_rate_episode import HighHeartRateEpisode
from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    DailyTargetResult,
    LowActivityResult,
    PatientAlert,
    TrainingMinutesExceeded,
)
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime


class AlertHighHeartRateOut(ApiModel):
    """«Пульс до 142 при пороге 130, 6 мин»."""

    started_at: UtcDatetime
    last_at: UtcDatetime
    duration_minutes: int
    max_heart_rate: int
    threshold_bpm: int

    @classmethod
    def from_domain(cls, episode: HighHeartRateEpisode) -> Self:
        return cls(
            started_at=episode.started_at,
            last_at=episode.last_at,
            duration_minutes=episode.duration_minutes,
            max_heart_rate=episode.max_heart_rate.bpm,
            threshold_bpm=episode.threshold_bpm,
        )


class AlertDailyTargetOut(ApiModel):
    """«12 из 30 мин, 6 200 из 10 000 шагов»."""

    training_minutes: int
    target_training_minutes: int
    steps: int
    target_steps: int

    @classmethod
    def from_domain(cls, result: DailyTargetResult) -> Self:
        return cls(
            training_minutes=result.training_minutes,
            target_training_minutes=result.target_training_minutes,
            steps=result.steps,
            target_steps=result.target_steps,
        )


class AlertLowActivityOut(ApiModel):
    steps: int
    threshold_steps: int

    @classmethod
    def from_domain(cls, result: LowActivityResult) -> Self:
        return cls(steps=result.steps, threshold_steps=result.threshold_steps)


class AlertTrainingMinutesExceededOut(ApiModel):
    training_minutes: int
    threshold_minutes: int


class ClinicianAlertOut(ApiModel):
    """Оповещение пациента врача. Заполнено поле значений своего вида, остальные — null."""

    alert_id: UUID
    patient_id: UUID
    kind: AlertKind
    day: date | None
    created_at: UtcDatetime
    is_viewed: bool
    viewed_at: UtcDatetime | None
    high_heart_rate: AlertHighHeartRateOut | None
    daily_target: AlertDailyTargetOut | None
    low_activity: AlertLowActivityOut | None
    training_minutes_exceeded: AlertTrainingMinutesExceededOut | None

    @classmethod
    def from_domain(cls, alert: PatientAlert) -> Self:
        details = alert.details
        return cls(
            alert_id=alert.id,
            patient_id=alert.patient_id,
            kind=alert.kind,
            day=alert.day,
            created_at=alert.created_at,
            is_viewed=alert.is_viewed,
            viewed_at=alert.viewed_at,
            high_heart_rate=AlertHighHeartRateOut.from_domain(details)
            if isinstance(details, HighHeartRateEpisode)
            else None,
            daily_target=AlertDailyTargetOut.from_domain(details)
            if isinstance(details, DailyTargetResult)
            else None,
            training_minutes_exceeded=AlertTrainingMinutesExceededOut(
                training_minutes=details.training_minutes,
                threshold_minutes=details.threshold_minutes,
            )
            if isinstance(details, TrainingMinutesExceeded)
            else None,
            low_activity=AlertLowActivityOut.from_domain(details)
            if isinstance(details, LowActivityResult)
            else None,
        )
