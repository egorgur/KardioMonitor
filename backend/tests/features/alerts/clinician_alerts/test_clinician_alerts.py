"""Слайс «Оповещения врачу»: списки, фильтр по виду, отметка «просмотрено» (ТР-08, п. 9)."""

from datetime import UTC, date, datetime, timedelta
from uuid import uuid4

from httpx import AsyncClient

from kardiomonitor.features.alerts.domain.high_heart_rate_episode import HighHeartRateEpisode
from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    DailyTargetResult,
    LowActivityResult,
    PatientAlert,
)
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.features.patients.domain.patient import Patient
from tests.features.alerts.ports import OTHER, Ports
from tests.features.rehab.plans import PATIENT

CREATED = datetime(2026, 10, 11, 17, 0, tzinfo=UTC)
DAY = date(2026, 10, 11)


def _missed(patient: Patient, hours: int = 0) -> PatientAlert:
    result = DailyTargetResult(12, 30, 6_200, 10_000)
    return PatientAlert.daily(
        patient.id, AlertKind.DAILY_TARGET_MISSED, DAY, result, now=CREATED + timedelta(hours=hours)
    )


def _high(patient: Patient) -> PatientAlert:
    episode = HighHeartRateEpisode(
        CREATED - timedelta(hours=7), CREATED - timedelta(hours=6, minutes=55), HeartRate(142), 130
    )
    return PatientAlert.high_heart_rate(patient.id, episode, now=CREATED + timedelta(hours=1))


async def test_врач_видит_оповещения_своих_пациентов_новые_сверху(
    client: AsyncClient, ports: Ports
) -> None:
    for alert in (_missed(PATIENT), _high(PATIENT), _missed(OTHER)):
        await ports.journal.save(alert)

    body = (await client.get("/api/v1/alerts")).json()

    assert [alert["kind"] for alert in body] == ["high_heart_rate", "daily_target_missed"]
    assert body[0]["high_heart_rate"] == {
        "started_at": "2026-10-11T10:00:00Z",
        "last_at": "2026-10-11T10:05:00Z",
        "duration_minutes": 6,
        "max_heart_rate": 142,
        "threshold_bpm": 130,
    }
    assert body[1]["daily_target"] == {
        "training_minutes": 12,
        "target_training_minutes": 30,
        "steps": 6200,
        "target_steps": 10000,
    }
    assert body[1]["day"] == "2026-10-11"
    assert body[1]["is_viewed"] is False


async def test_фильтр_по_виду_оповещения(client: AsyncClient, ports: Ports) -> None:
    low = PatientAlert.daily(
        PATIENT.id, AlertKind.LOW_ACTIVITY, DAY, LowActivityResult(400, 1_000), now=CREATED
    )
    for alert in (_missed(PATIENT), low):
        await ports.journal.save(alert)

    body = (await client.get("/api/v1/alerts", params={"kind": "low_activity"})).json()

    assert [(alert["kind"], alert["low_activity"]) for alert in body] == [
        ("low_activity", {"steps": 400, "threshold_steps": 1000})
    ]


async def test_оповещения_в_карточке_пациента(client: AsyncClient, ports: Ports) -> None:
    for alert in (_missed(PATIENT), _missed(PATIENT, hours=24)):
        await ports.journal.save(alert)

    body = (await client.get(f"/api/v1/patients/{PATIENT.id}/alerts?limit=1")).json()

    assert [alert["created_at"] for alert in body] == ["2026-10-12T17:00:00Z"]


async def test_врач_отметил_просмотрено_счётчик_в_списке_пациентов_уменьшился(
    client: AsyncClient, ports: Ports
) -> None:
    first, second = _missed(PATIENT), _high(PATIENT)
    for alert in (first, second):
        await ports.journal.save(alert)
    url = f"/api/v1/patients/{PATIENT.id}/alerts/{first.id}/viewed"

    before = (await client.get("/api/v1/alerts/unviewed-counts")).json()
    marked = await client.post(url)
    again = await client.post(url)
    after = (await client.get("/api/v1/alerts/unviewed-counts")).json()

    assert before == [{"patient_id": str(PATIENT.id), "unviewed_alerts": 2}]
    assert (marked.status_code, again.status_code) == (204, 204)
    assert after == [{"patient_id": str(PATIENT.id), "unviewed_alerts": 1}]
    assert ports.journal.alerts[first.id].viewed_at == ports.clock.current
    assert ports.transaction.commits == 2


async def test_чужое_или_неизвестное_оповещение_не_найдено(
    client: AsyncClient, ports: Ports
) -> None:
    foreign = _missed(OTHER)
    await ports.journal.save(foreign)

    via_own_patient = await client.post(f"/api/v1/patients/{PATIENT.id}/alerts/{foreign.id}/viewed")
    unknown = await client.post(f"/api/v1/patients/{PATIENT.id}/alerts/{uuid4()}/viewed")

    assert via_own_patient.status_code == unknown.status_code == 404
    assert via_own_patient.json()["code"] == "alert_not_found"
    assert not ports.journal.alerts[foreign.id].is_viewed
    assert ports.transaction.commits == 0
