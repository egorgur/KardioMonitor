"""Контракт API слайса «Оповещения на телефон» (api-contract.md)."""

from collections.abc import Sequence
from datetime import date
from typing import Self
from uuid import UUID

from kardiomonitor.features.alerts.domain.alert_journal import FeedEntry
from kardiomonitor.features.alerts.domain.high_heart_rate_episode import HighHeartRateEpisode
from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    DailyTargetResult,
    LowActivityResult,
    PatientAlert,
    TrainingMinutesExceeded,
)
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime


class PhoneHighHeartRateOut(ApiModel):
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


class PhoneDailyTargetOut(ApiModel):
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


class PhoneLowActivityOut(ApiModel):
    steps: int
    threshold_steps: int

    @classmethod
    def from_domain(cls, result: LowActivityResult) -> Self:
        return cls(steps=result.steps, threshold_steps=result.threshold_steps)


class PhoneTrainingMinutesExceededOut(ApiModel):
    training_minutes: int
    threshold_minutes: int


class PhoneAlertOut(ApiModel):
    """Оповещение для пациента: значения, без текста — тексты утверждает врач (В-19)."""

    alert_id: UUID
    kind: AlertKind
    day: date | None
    created_at: UtcDatetime
    high_heart_rate: PhoneHighHeartRateOut | None
    daily_target: PhoneDailyTargetOut | None
    low_activity: PhoneLowActivityOut | None
    training_minutes_exceeded: PhoneTrainingMinutesExceededOut | None

    @classmethod
    def from_domain(cls, alert: PatientAlert) -> Self:
        details = alert.details
        return cls(
            alert_id=alert.id,
            kind=alert.kind,
            day=alert.day,
            created_at=alert.created_at,
            high_heart_rate=PhoneHighHeartRateOut.from_domain(details)
            if isinstance(details, HighHeartRateEpisode)
            else None,
            daily_target=PhoneDailyTargetOut.from_domain(details)
            if isinstance(details, DailyTargetResult)
            else None,
            training_minutes_exceeded=PhoneTrainingMinutesExceededOut(
                training_minutes=details.training_minutes,
                threshold_minutes=details.threshold_minutes,
            )
            if isinstance(details, TrainingMinutesExceeded)
            else None,
            low_activity=PhoneLowActivityOut.from_domain(details)
            if isinstance(details, LowActivityResult)
            else None,
        )


class AlertFeedOut(ApiModel):
    """Новые и изменённые оповещения после курсора `after`.

    `cursor` — передать как `after` при следующей синхронизации. `has_more` — запросить ещё раз
    сразу. Изменённое оповещение (эпизод высокого пульса продлился) приходит снова с тем же
    `alert_id`: телефон обновляет запись и второй раз уведомление не показывает.
    """

    alerts: list[PhoneAlertOut]
    cursor: int
    has_more: bool

    @classmethod
    def from_domain(cls, entries: Sequence[FeedEntry], *, after: int, limit: int) -> Self:
        return cls(
            alerts=[PhoneAlertOut.from_domain(entry.alert) for entry in entries[:limit]],
            cursor=entries[min(len(entries), limit) - 1].position if entries else after,
            has_more=len(entries) > limit,
        )
