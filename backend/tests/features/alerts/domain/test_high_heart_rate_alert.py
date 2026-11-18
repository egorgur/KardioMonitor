"""Оповещение «высокий пульс» — одно на эпизод (ТР-08, п. 5, 6)."""

from dataclasses import replace
from datetime import UTC, datetime, timedelta

from kardiomonitor.features.alerts.domain.high_heart_rate_alert import alert_for_episode
from kardiomonitor.features.alerts.domain.high_heart_rate_episode import HighHeartRateEpisode
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from tests.features.rehab.plans import PATIENT

TEN = datetime(2026, 10, 12, 7, 0, tzinfo=UTC)
NOW = datetime(2026, 10, 12, 8, 0, tzinfo=UTC)


def _episode(first_minute: int, last_minute: int, max_bpm: int = 140) -> HighHeartRateEpisode:
    return HighHeartRateEpisode(
        started_at=TEN + timedelta(minutes=first_minute),
        last_at=TEN + timedelta(minutes=last_minute),
        max_heart_rate=HeartRate(max_bpm),
        threshold_bpm=130,
    )


def test_новый_эпизод_новое_оповещение() -> None:
    alert = alert_for_episode(PATIENT.id, _episode(1, 6), [], now=NOW)

    assert alert is not None
    assert alert.episode == _episode(1, 6)
    assert alert.created_at == NOW
    assert not alert.is_viewed


def test_тот_же_эпизод_повторно_не_создаёт_и_не_меняет_оповещение() -> None:
    recorded = alert_for_episode(PATIENT.id, _episode(1, 6), [], now=NOW)
    assert recorded is not None

    assert alert_for_episode(PATIENT.id, _episode(1, 6), [recorded], now=NOW) is None
    assert alert_for_episode(PATIENT.id, _episode(2, 3), [recorded], now=NOW) is None


def test_продолжение_эпизода_в_следующем_пакете_продлевает_его_оповещение() -> None:
    recorded = alert_for_episode(PATIENT.id, _episode(1, 6), [], now=NOW)
    assert recorded is not None
    viewed = replace(recorded, viewed_at=NOW)

    extended = alert_for_episode(PATIENT.id, _episode(9, 20, 155), [viewed], now=NOW)

    assert extended is not None
    assert extended.id == recorded.id
    assert extended.episode == _episode(1, 20, 155)
    assert not extended.is_viewed, "продлённый эпизод врач видит заново"


def test_эпизод_после_долгого_перерыва_новое_оповещение() -> None:
    recorded = alert_for_episode(PATIENT.id, _episode(1, 6), [], now=NOW)
    assert recorded is not None

    later = alert_for_episode(PATIENT.id, _episode(13, 15), [recorded], now=NOW)

    assert later is not None
    assert later.id != recorded.id
