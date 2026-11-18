"""Слайс «Оповещения на телефон»: лента по курсору `after` (ТР-08, п. 10)."""

from datetime import UTC, date, datetime, timedelta

import pytest
from httpx import AsyncClient, QueryParams

from kardiomonitor.features.alerts.domain.high_heart_rate_episode import HighHeartRateEpisode
from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    DailyTargetResult,
    PatientAlert,
    TrainingMinutesExceeded,
)
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from tests.features.alerts.ports import OTHER, Ports
from tests.features.rehab.plans import PATIENT

URL = "/api/v1/me/alerts"
NOW = datetime(2026, 10, 11, 17, 0, tzinfo=UTC)


def _met(day: int) -> PatientAlert:
    result = DailyTargetResult(35, 30, 11_000, 10_000)
    return PatientAlert.daily(
        PATIENT.id, AlertKind.DAILY_TARGET_MET, date(2026, 10, day), result, now=NOW
    )


async def test_телефон_забирает_только_новые_оповещения_по_курсору(
    client: AsyncClient, ports: Ports
) -> None:
    await ports.journal.save(_met(10))
    await ports.journal.save(_met(11))

    first = (await client.get(URL)).json()
    nothing_new = (await client.get(URL, params={"after": first["cursor"]})).json()
    await ports.journal.save(_met(12))
    newer = (await client.get(URL, params={"after": first["cursor"]})).json()

    assert [alert["day"] for alert in first["alerts"]] == ["2026-10-10", "2026-10-11"]
    assert first["has_more"] is False
    assert nothing_new == {"alerts": [], "cursor": first["cursor"], "has_more": False}
    assert [alert["day"] for alert in newer["alerts"]] == ["2026-10-12"]
    assert first["alerts"][0]["daily_target"]["training_minutes"] == 35
    assert "is_viewed" not in first["alerts"][0]


async def test_продлённый_эпизод_приходит_снова_с_тем_же_идентификатором(
    client: AsyncClient, ports: Ports
) -> None:
    episode = HighHeartRateEpisode(NOW, NOW + timedelta(minutes=2), HeartRate(140), 130)
    alert = PatientAlert.high_heart_rate(PATIENT.id, episode, now=NOW)
    await ports.journal.save(alert)
    first = (await client.get(URL)).json()

    longer = HighHeartRateEpisode(NOW, NOW + timedelta(minutes=9), HeartRate(151), 130)
    await ports.journal.save(alert.with_episode(longer))
    update = (await client.get(URL, params={"after": first["cursor"]})).json()

    assert [a["alert_id"] for a in update["alerts"]] == [str(alert.id)]
    assert update["alerts"][0]["high_heart_rate"]["max_heart_rate"] == 151


async def test_оповещения_другого_пациента_телефону_не_приходят(
    client: AsyncClient, ports: Ports
) -> None:
    result = DailyTargetResult(0, 30, 0, 10_000)
    await ports.journal.save(
        PatientAlert.daily(
            OTHER.id, AlertKind.DAILY_TARGET_MISSED, date(2026, 10, 11), result, now=NOW
        )
    )

    body = (await client.get(URL)).json()

    assert body == {"alerts": [], "cursor": 0, "has_more": False}


@pytest.mark.parametrize("legacy_count", [99, 100, 101])
async def test_старый_телефон_пагинирует_только_известные_виды(
    client: AsyncClient,
    ports: Ports,
    legacy_count: int,
) -> None:
    for index in range(legacy_count):
        day = date(2026, 1, 1) + timedelta(days=index)
        await ports.journal.save(
            PatientAlert.daily(
                PATIENT.id,
                AlertKind.TRAINING_MINUTES_EXCEEDED,
                day,
                TrainingMinutesExceeded(61, 60),
                now=NOW,
            )
        )
        await ports.journal.save(
            PatientAlert.daily(
                PATIENT.id,
                AlertKind.DAILY_TARGET_MET,
                day,
                DailyTargetResult(61, 30, 11000, 10000),
                now=NOW,
            )
        )
    first = (await client.get(URL)).json()
    assert len(first["alerts"]) == min(100, legacy_count)
    assert first["has_more"] is (legacy_count > 100)
    assert first["cursor"] == 2 * min(100, legacy_count)
    assert {a["kind"] for a in first["alerts"]} == {"daily_target_met"}
    second = (await client.get(URL, params={"after": first["cursor"]})).json()
    assert len(second["alerts"]) == max(0, legacy_count - 100)
    assert second["has_more"] is False


async def test_новый_телефон_сообщает_все_пять_видов(client: AsyncClient, ports: Ports) -> None:
    alert = PatientAlert.daily(
        PATIENT.id,
        AlertKind.TRAINING_MINUTES_EXCEEDED,
        date(2026, 10, 11),
        TrainingMinutesExceeded(61, 60),
        now=NOW,
    )
    await ports.journal.save(alert)
    params = QueryParams([("supported_kinds", kind.value) for kind in AlertKind])
    body = (await client.get(URL, params=params)).json()
    assert body["alerts"][0]["training_minutes_exceeded"] == {
        "training_minutes": 61,
        "threshold_minutes": 60,
    }
    assert body["alerts"][0]["day"] == "2026-10-11"
    assert (await client.get(URL, params={"supported_kinds": "unknown"})).status_code == 422
