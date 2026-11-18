"""Сценарии жизненного цикла запроса непрерывного пульса."""

from kardiomonitor.features.monitoring.domain.live_heart_rate_request import (
    LiveHeartRateRequest,
    LiveHeartRateRequestId,
    LiveHeartRateRequestReport,
    LiveHeartRateRequests,
)
from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.shared_kernel import Clock, Transaction


async def create_request(
    patient_id: PatientId,
    duration_minutes: int,
    *,
    requests: LiveHeartRateRequests,
    clock: Clock,
    transaction: Transaction,
) -> LiveHeartRateRequest | None:
    now = clock.now()
    active = await requests.active_of(patient_id)
    if active is not None:
        expired = active.expire(now)
        if expired == active:
            return None
        await requests.save(expired)
    request = LiveHeartRateRequest.create(patient_id, duration_minutes, now=now)
    if not await requests.add_if_no_active(request):
        return None
    await transaction.commit()
    return request


async def active_request(
    patient_id: PatientId,
    *,
    requests: LiveHeartRateRequests,
    clock: Clock,
    transaction: Transaction,
) -> LiveHeartRateRequest | None:
    request = await requests.active_of(patient_id)
    if request is None:
        return None
    current = request.expire(clock.now())
    if current != request:
        await requests.save(current)
        await transaction.commit()
        return None
    return current


async def latest_request(
    patient_id: PatientId,
    *,
    requests: LiveHeartRateRequests,
    clock: Clock,
    transaction: Transaction,
) -> LiveHeartRateRequest | None:
    request = await requests.latest_of(patient_id)
    if request is None:
        return None
    current = request.expire(clock.now())
    if current != request:
        await requests.save(current)
        await transaction.commit()
    return current


async def report_request_status(
    patient_id: PatientId,
    request_id: LiveHeartRateRequestId,
    report: LiveHeartRateRequestReport,
    *,
    requests: LiveHeartRateRequests,
    clock: Clock,
    transaction: Transaction,
) -> LiveHeartRateRequest | None:
    request = await requests.by_id(request_id)
    if request is None or request.patient_id != patient_id:
        return None
    current = request.report(report.status, report.failure_reason, now=clock.now())
    if current != request:
        await requests.save(current)
    await transaction.commit()
    return current
