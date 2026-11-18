"""Оповещение «высокий пульс»: одно на эпизод, даже если эпизод пришёл несколькими пакетами."""

from collections.abc import Sequence
from datetime import datetime, timedelta

from kardiomonitor.features.alerts.domain.high_heart_rate_episode import (
    MAX_EPISODE_BREAK,
    HighHeartRateEpisode,
)
from kardiomonitor.features.alerts.domain.patient_alert import PatientAlert
from kardiomonitor.features.patients.domain.patient import PatientId

JOIN_WINDOW = MAX_EPISODE_BREAK + timedelta(minutes=1)
"""Насколько раньше и позже эпизода искать записанные: перерыв и ещё одна минута."""


def alert_for_episode(
    patient_id: PatientId,
    episode: HighHeartRateEpisode,
    recorded: Sequence[PatientAlert],
    *,
    now: datetime,
) -> PatientAlert | None:
    """Что сохранить: новое оповещение или продлённое записанное; None — эпизод уже записан.

    `recorded` — оповещения «высокий пульс» пациента рядом с эпизодом (`JOIN_WINDOW`).
    Повтор тех же минут ничего не меняет (ТР-08, п. 6); продолжение эпизода в следующем пакете
    продлевает его оповещение, а не создаёт второе (п. 5).
    """
    continued = [
        (alert, alert.episode)
        for alert in sorted(recorded, key=lambda alert: alert.created_at)
        if alert.episode is not None and alert.episode.continues(episode)
    ]
    if not continued:
        return PatientAlert.high_heart_rate(patient_id, episode, now=now)
    alert, current = continued[0]
    joined = current.joined(episode)
    return None if joined == current else alert.with_episode(joined)
