"""Запросы врача на непрерывное измерение пульса в базе."""

from sqlalchemy import select
from sqlalchemy.dialects.sqlite import insert
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.monitoring.data.tables import LiveHeartRateRequestRow
from kardiomonitor.features.monitoring.domain.live_heart_rate_request import (
    LiveHeartRateFailureReason,
    LiveHeartRateRequest,
    LiveHeartRateRequestId,
    LiveHeartRateRequestStatus,
)
from kardiomonitor.features.patients.domain.patient import PatientId

_ACTIVE_STATUSES = (
    LiveHeartRateRequestStatus.WAITING_FOR_PHONE,
    LiveHeartRateRequestStatus.RUNNING,
)


class SqlLiveHeartRateRequests:
    """Реализация `LiveHeartRateRequests`; транзакцию фиксирует сценарий."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def add_if_no_active(self, request: LiveHeartRateRequest) -> bool:
        statement = (
            insert(LiveHeartRateRequestRow)
            .values(**_values(request))
            .on_conflict_do_nothing()
            .returning(LiveHeartRateRequestRow.id)
        )
        inserted = await self._session.scalar(statement)
        return inserted is not None

    async def save(self, request: LiveHeartRateRequest) -> None:
        row = await self._session.get(LiveHeartRateRequestRow, request.id)
        if row is None:
            raise ValueError("запрос пульса не найден при сохранении")
        _fill(row, request)
        await self._session.flush()

    async def by_id(self, request_id: LiveHeartRateRequestId) -> LiveHeartRateRequest | None:
        row = await self._session.get(LiveHeartRateRequestRow, request_id)
        return None if row is None else _to_request(row)

    async def active_of(self, patient_id: PatientId) -> LiveHeartRateRequest | None:
        row = await self._session.scalar(
            select(LiveHeartRateRequestRow)
            .where(
                LiveHeartRateRequestRow.patient_id == patient_id,
                LiveHeartRateRequestRow.status.in_(_ACTIVE_STATUSES),
            )
            .order_by(LiveHeartRateRequestRow.requested_at.desc())
            .limit(1)
        )
        return None if row is None else _to_request(row)

    async def latest_of(self, patient_id: PatientId) -> LiveHeartRateRequest | None:
        row = await self._session.scalar(
            select(LiveHeartRateRequestRow)
            .where(LiveHeartRateRequestRow.patient_id == patient_id)
            .order_by(LiveHeartRateRequestRow.requested_at.desc())
            .limit(1)
        )
        return None if row is None else _to_request(row)


def _values(request: LiveHeartRateRequest) -> dict[str, object]:
    return {
        "id": request.id,
        "patient_id": request.patient_id,
        "duration_minutes": request.duration_minutes,
        "requested_at": request.requested_at,
        "expires_at": request.expires_at,
        "status": request.status,
        "started_at": request.started_at,
        "finished_at": request.finished_at,
        "failure_reason": request.failure_reason,
    }


def _fill(row: LiveHeartRateRequestRow, request: LiveHeartRateRequest) -> None:
    row.status = request.status
    row.started_at = request.started_at
    row.finished_at = request.finished_at
    row.failure_reason = request.failure_reason


def _to_request(row: LiveHeartRateRequestRow) -> LiveHeartRateRequest:
    return LiveHeartRateRequest(
        id=LiveHeartRateRequestId(row.id),
        patient_id=PatientId(row.patient_id),
        duration_minutes=row.duration_minutes,
        requested_at=row.requested_at,
        expires_at=row.expires_at,
        status=LiveHeartRateRequestStatus(row.status),
        started_at=row.started_at,
        finished_at=row.finished_at,
        failure_reason=LiveHeartRateFailureReason(row.failure_reason)
        if row.failure_reason is not None
        else None,
    )
