"""Домен запроса непрерывного пульса (ТР-09)."""

from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from kardiomonitor.features.monitoring.domain.live_heart_rate_request import (
    LIVE_HEART_RATE_REQUEST_TTL,
    InvalidLiveHeartRateRequestTransition,
    LiveHeartRateFailureReason,
    LiveHeartRateRequest,
    LiveHeartRateRequestId,
    LiveHeartRateRequestStatus,
)
from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.shared_kernel import DomainRuleViolation

NOW = datetime(2026, 11, 1, 9, tzinfo=UTC)
PATIENT_ID = PatientId(UUID("00000000-0000-0000-0000-000000000001"))
REQUEST_ID = LiveHeartRateRequestId(UUID("00000000-0000-0000-0000-000000000002"))


def request(duration: int = 10) -> LiveHeartRateRequest:
    return LiveHeartRateRequest.create(PATIENT_ID, duration, now=NOW, request_id=REQUEST_ID)


def test_новый_запрос_ожидает_телефон_два_часа() -> None:
    created = request()

    assert created.status is LiveHeartRateRequestStatus.WAITING_FOR_PHONE
    assert created.expires_at == NOW + LIVE_HEART_RATE_REQUEST_TTL
    assert created.started_at is None
    assert created.finished_at is None
    assert created.failure_reason is None


@pytest.mark.parametrize("duration", [0, 61])
def test_длительность_только_от_одной_до_шестидесяти_минут(duration: int) -> None:
    with pytest.raises(DomainRuleViolation) as error:
        request(duration)

    assert error.value.code == "invalid_live_heart_rate_request_duration"


def test_ожидание_работа_завершение_и_повтор_идемпотентны() -> None:
    started_at = NOW + timedelta(minutes=1)
    finished_at = NOW + timedelta(minutes=11)

    running = request().report(LiveHeartRateRequestStatus.RUNNING, None, now=started_at)
    repeated = running.report(LiveHeartRateRequestStatus.RUNNING, None, now=finished_at)
    completed = repeated.report(LiveHeartRateRequestStatus.COMPLETED, None, now=finished_at)

    assert repeated == running
    assert completed.started_at == started_at
    assert completed.finished_at == finished_at


def test_просроченное_ожидание_становится_невыполненным_в_момент_срока() -> None:
    original = request()

    expired = original.expire(original.expires_at + timedelta(hours=1))

    assert expired.status is LiveHeartRateRequestStatus.FAILED
    assert expired.failure_reason is LiveHeartRateFailureReason.EXPIRED
    assert expired.finished_at == original.expires_at


def test_нельзя_завершить_ещё_не_начатое_измерение() -> None:
    with pytest.raises(InvalidLiveHeartRateRequestTransition):
        request().report(LiveHeartRateRequestStatus.COMPLETED, None, now=NOW)
