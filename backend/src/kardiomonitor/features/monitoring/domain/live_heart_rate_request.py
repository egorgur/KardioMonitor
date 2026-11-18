"""Запрос врача на непрерывное измерение пульса (ТР-09)."""

from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from enum import StrEnum
from typing import NewType, Protocol
from uuid import UUID, uuid4

from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.shared_kernel import DomainRuleViolation

LiveHeartRateRequestId = NewType("LiveHeartRateRequestId", UUID)

MIN_REQUEST_DURATION_MINUTES = 1
MAX_REQUEST_DURATION_MINUTES = 60
DEFAULT_REQUEST_DURATION_MINUTES = 10
LIVE_HEART_RATE_REQUEST_TTL = timedelta(hours=2)


class LiveHeartRateRequestStatus(StrEnum):
    WAITING_FOR_PHONE = "waiting_for_phone"
    RUNNING = "running"
    COMPLETED = "completed"
    STOPPED_BY_PATIENT = "stopped_by_patient"
    FAILED = "failed"

    @property
    def is_terminal(self) -> bool:
        return self in {
            LiveHeartRateRequestStatus.COMPLETED,
            LiveHeartRateRequestStatus.STOPPED_BY_PATIENT,
            LiveHeartRateRequestStatus.FAILED,
        }


class LiveHeartRateFailureReason(StrEnum):
    EXPIRED = "expired"
    BAND_UNAVAILABLE = "band_unavailable"
    NOTIFICATION_PERMISSION_DENIED = "notification_permission_denied"
    COMPANION_ASSOCIATION_MISSING = "companion_association_missing"
    MEASUREMENT_FAILED = "measurement_failed"


class InvalidLiveHeartRateRequestTransition(Exception):
    """Телефон сообщил невозможный переход состояния запроса."""


@dataclass(frozen=True, slots=True)
class LiveHeartRateRequestReport:
    """Одно идемпотентное сообщение телефона о состоянии запроса."""

    status: LiveHeartRateRequestStatus
    failure_reason: LiveHeartRateFailureReason | None = None

    def __post_init__(self) -> None:
        _require_reportable(self.status, self.failure_reason)


@dataclass(frozen=True, slots=True)
class LiveHeartRateRequest:
    id: LiveHeartRateRequestId
    patient_id: PatientId
    duration_minutes: int
    requested_at: datetime
    expires_at: datetime
    status: LiveHeartRateRequestStatus
    started_at: datetime | None = None
    finished_at: datetime | None = None
    failure_reason: LiveHeartRateFailureReason | None = None

    def __post_init__(self) -> None:
        _validate_duration(self.duration_minutes)
        for moment in (self.requested_at, self.expires_at, self.started_at, self.finished_at):
            if moment is not None and moment.tzinfo is None:
                raise DomainRuleViolation(
                    f"время запроса пульса без часового пояса: {moment}", code="naive_datetime"
                )
        if self.expires_at <= self.requested_at:
            raise DomainRuleViolation(
                "срок действия запроса должен быть позже создания",
                code="invalid_live_heart_rate_request",
            )
        _validate_state(self)

    @classmethod
    def create(
        cls,
        patient_id: PatientId,
        duration_minutes: int,
        *,
        now: datetime,
        request_id: LiveHeartRateRequestId | None = None,
    ) -> "LiveHeartRateRequest":
        return cls(
            id=request_id or LiveHeartRateRequestId(uuid4()),
            patient_id=patient_id,
            duration_minutes=duration_minutes,
            requested_at=now,
            expires_at=now + LIVE_HEART_RATE_REQUEST_TTL,
            status=LiveHeartRateRequestStatus.WAITING_FOR_PHONE,
        )

    @property
    def is_active(self) -> bool:
        return not self.status.is_terminal

    def expire(self, now: datetime) -> "LiveHeartRateRequest":
        if self.status is not LiveHeartRateRequestStatus.WAITING_FOR_PHONE:
            return self
        if now < self.expires_at:
            return self
        return replace(
            self,
            status=LiveHeartRateRequestStatus.FAILED,
            finished_at=self.expires_at,
            failure_reason=LiveHeartRateFailureReason.EXPIRED,
        )

    def report(
        self,
        status: LiveHeartRateRequestStatus,
        failure_reason: LiveHeartRateFailureReason | None,
        *,
        now: datetime,
    ) -> "LiveHeartRateRequest":
        current = self.expire(now)
        if _is_same_report(current, status, failure_reason):
            return current
        _require_reportable(status, failure_reason)
        if current.status is LiveHeartRateRequestStatus.WAITING_FOR_PHONE:
            return current._from_waiting(status, failure_reason, now)
        if current.status is LiveHeartRateRequestStatus.RUNNING:
            return current._from_running(status, failure_reason, now)
        raise InvalidLiveHeartRateRequestTransition(
            f"запрос уже завершён со статусом {current.status}"
        )

    def _from_waiting(
        self,
        status: LiveHeartRateRequestStatus,
        reason: LiveHeartRateFailureReason | None,
        now: datetime,
    ) -> "LiveHeartRateRequest":
        if status is LiveHeartRateRequestStatus.RUNNING:
            return replace(self, status=status, started_at=now)
        if status is LiveHeartRateRequestStatus.FAILED:
            return replace(self, status=status, finished_at=now, failure_reason=reason)
        raise InvalidLiveHeartRateRequestTransition("ожидающий запрос ещё не выполнялся")

    def _from_running(
        self,
        status: LiveHeartRateRequestStatus,
        reason: LiveHeartRateFailureReason | None,
        now: datetime,
    ) -> "LiveHeartRateRequest":
        if status in {
            LiveHeartRateRequestStatus.COMPLETED,
            LiveHeartRateRequestStatus.STOPPED_BY_PATIENT,
            LiveHeartRateRequestStatus.FAILED,
        }:
            return replace(self, status=status, finished_at=now, failure_reason=reason)
        raise InvalidLiveHeartRateRequestTransition("измерение уже началось")


class LiveHeartRateRequests(Protocol):
    async def add_if_no_active(self, request: LiveHeartRateRequest) -> bool: ...

    async def save(self, request: LiveHeartRateRequest) -> None: ...

    async def by_id(self, request_id: LiveHeartRateRequestId) -> LiveHeartRateRequest | None: ...

    async def active_of(self, patient_id: PatientId) -> LiveHeartRateRequest | None: ...

    async def latest_of(self, patient_id: PatientId) -> LiveHeartRateRequest | None: ...


def _validate_duration(duration_minutes: int) -> None:
    if not MIN_REQUEST_DURATION_MINUTES <= duration_minutes <= MAX_REQUEST_DURATION_MINUTES:
        raise DomainRuleViolation(
            f"длительность запроса должна быть от {MIN_REQUEST_DURATION_MINUTES} до "
            f"{MAX_REQUEST_DURATION_MINUTES} минут",
            code="invalid_live_heart_rate_request_duration",
        )


def _validate_state(request: LiveHeartRateRequest) -> None:
    if request.status is LiveHeartRateRequestStatus.WAITING_FOR_PHONE:
        valid = request.started_at is None and request.finished_at is None
    elif request.status is LiveHeartRateRequestStatus.RUNNING:
        valid = request.started_at is not None and request.finished_at is None
    else:
        valid = request.finished_at is not None
    reason_is_valid = (request.status is LiveHeartRateRequestStatus.FAILED) == (
        request.failure_reason is not None
    )
    if not valid or not reason_is_valid:
        raise DomainRuleViolation(
            "моменты и причина не соответствуют состоянию запроса",
            code="invalid_live_heart_rate_request",
        )


def _require_reportable(
    status: LiveHeartRateRequestStatus, reason: LiveHeartRateFailureReason | None
) -> None:
    if status is LiveHeartRateRequestStatus.WAITING_FOR_PHONE:
        raise InvalidLiveHeartRateRequestTransition("телефон не может вернуть запрос в ожидание")
    if (status is LiveHeartRateRequestStatus.FAILED) != (reason is not None):
        raise InvalidLiveHeartRateRequestTransition(
            "причина обязательна только для состояния failed"
        )


def _is_same_report(
    request: LiveHeartRateRequest,
    status: LiveHeartRateRequestStatus,
    reason: LiveHeartRateFailureReason | None,
) -> bool:
    return request.status is status and request.failure_reason is reason
