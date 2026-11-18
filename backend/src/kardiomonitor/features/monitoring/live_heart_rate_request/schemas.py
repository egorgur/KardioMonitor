"""Контракт API запроса непрерывного пульса (ТР-09)."""

from typing import Annotated, Self
from uuid import UUID

from pydantic import Field, model_validator

from kardiomonitor.features.monitoring.domain.live_heart_rate_request import (
    DEFAULT_REQUEST_DURATION_MINUTES,
    MAX_REQUEST_DURATION_MINUTES,
    MIN_REQUEST_DURATION_MINUTES,
    LiveHeartRateFailureReason,
    LiveHeartRateRequest,
    LiveHeartRateRequestReport,
    LiveHeartRateRequestStatus,
)
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime


class CreateLiveHeartRateRequestIn(ApiModel):
    duration_minutes: Annotated[
        int,
        Field(ge=MIN_REQUEST_DURATION_MINUTES, le=MAX_REQUEST_DURATION_MINUTES),
    ] = DEFAULT_REQUEST_DURATION_MINUTES


class ReportLiveHeartRateRequestIn(ApiModel):
    status: LiveHeartRateRequestStatus
    failure_reason: LiveHeartRateFailureReason | None = None

    @model_validator(mode="after")
    def report_is_consistent(self) -> Self:
        if self.status is LiveHeartRateRequestStatus.WAITING_FOR_PHONE:
            raise ValueError("телефон не может вернуть запрос в ожидание")
        if (self.status is LiveHeartRateRequestStatus.FAILED) != (self.failure_reason is not None):
            raise ValueError("причина обязательна только для состояния failed")
        return self

    def to_domain(self) -> LiveHeartRateRequestReport:
        return LiveHeartRateRequestReport(self.status, self.failure_reason)


class PhoneLiveHeartRateRequestOut(ApiModel):
    request_id: UUID
    duration_minutes: int
    requested_at: UtcDatetime
    expires_at: UtcDatetime
    status: LiveHeartRateRequestStatus
    started_at: UtcDatetime | None
    finished_at: UtcDatetime | None
    failure_reason: LiveHeartRateFailureReason | None

    @classmethod
    def from_domain(cls, request: LiveHeartRateRequest) -> Self:
        return cls(
            request_id=request.id,
            duration_minutes=request.duration_minutes,
            requested_at=request.requested_at,
            expires_at=request.expires_at,
            status=request.status,
            started_at=request.started_at,
            finished_at=request.finished_at,
            failure_reason=request.failure_reason,
        )


class ClinicianLiveHeartRateRequestOut(PhoneLiveHeartRateRequestOut):
    patient_id: UUID

    @classmethod
    def from_domain(cls, request: LiveHeartRateRequest) -> Self:
        return cls(
            **PhoneLiveHeartRateRequestOut.from_domain(request).model_dump(),
            patient_id=request.patient_id,
        )


class PhoneLiveHeartRateRequestEnvelope(ApiModel):
    request: PhoneLiveHeartRateRequestOut | None


class ClinicianLiveHeartRateRequestEnvelope(ApiModel):
    request: ClinicianLiveHeartRateRequestOut | None
