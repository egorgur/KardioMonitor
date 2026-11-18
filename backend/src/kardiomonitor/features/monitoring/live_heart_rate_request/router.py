"""Запрос непрерывного пульса: панель создаёт, телефон получает и меняет состояние."""

from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.monitoring.domain.live_heart_rate_request import (
    InvalidLiveHeartRateRequestTransition,
    LiveHeartRateRequestId,
    LiveHeartRateRequests,
)
from kardiomonitor.features.patients.api import AttendedPatients, CurrentPatient, bearer_scheme
from kardiomonitor.platform.web.problem import ProblemError
from kardiomonitor.shared_kernel import Clock, Transaction

from .manage_requests import (
    active_request,
    create_request,
    latest_request,
    report_request_status,
)
from .schemas import (
    ClinicianLiveHeartRateRequestEnvelope,
    ClinicianLiveHeartRateRequestOut,
    CreateLiveHeartRateRequestIn,
    PhoneLiveHeartRateRequestEnvelope,
    PhoneLiveHeartRateRequestOut,
    ReportLiveHeartRateRequestIn,
)

router = APIRouter(prefix="/api/v1", route_class=DishkaRoute)


@router.post(
    "/patients/{patient_id}/live-heart-rate-requests",
    status_code=201,
    tags=["Панель клиники"],
    summary="Запросить непрерывный пульс у пациента",
    dependencies=[Depends(session_scheme)],
)
async def request_live_heart_rate(
    patient_id: UUID,
    body: CreateLiveHeartRateRequestIn,
    patients: FromDishka[AttendedPatients],
    requests: FromDishka[LiveHeartRateRequests],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> ClinicianLiveHeartRateRequestOut:
    patient = await patients.require(patient_id)
    request = await create_request(
        patient.id,
        body.duration_minutes,
        requests=requests,
        clock=clock,
        transaction=transaction,
    )
    if request is None:
        raise ProblemError(
            status=409,
            code="active_live_heart_rate_request",
            title="У пациента уже есть активный запрос пульса",
        )
    return ClinicianLiveHeartRateRequestOut.from_domain(request)


@router.get(
    "/patients/{patient_id}/live-heart-rate-requests/latest",
    tags=["Панель клиники"],
    summary="Последний запрос непрерывного пульса",
    dependencies=[Depends(session_scheme)],
)
async def get_latest_live_heart_rate_request(
    patient_id: UUID,
    patients: FromDishka[AttendedPatients],
    requests: FromDishka[LiveHeartRateRequests],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> ClinicianLiveHeartRateRequestEnvelope:
    patient = await patients.require(patient_id)
    request = await latest_request(
        patient.id, requests=requests, clock=clock, transaction=transaction
    )
    return ClinicianLiveHeartRateRequestEnvelope(
        request=None if request is None else ClinicianLiveHeartRateRequestOut.from_domain(request)
    )


@router.get(
    "/me/live-heart-rate-request",
    tags=["Телефон"],
    summary="Получить активный запрос пульса от клиники",
    dependencies=[Depends(bearer_scheme)],
)
async def get_active_live_heart_rate_request(
    patient: FromDishka[CurrentPatient],
    requests: FromDishka[LiveHeartRateRequests],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> PhoneLiveHeartRateRequestEnvelope:
    request = await active_request(
        patient.id, requests=requests, clock=clock, transaction=transaction
    )
    return PhoneLiveHeartRateRequestEnvelope(
        request=None if request is None else PhoneLiveHeartRateRequestOut.from_domain(request)
    )


@router.put(
    "/me/live-heart-rate-requests/{request_id}/status",
    tags=["Телефон"],
    summary="Сообщить состояние запроса пульса",
    dependencies=[Depends(bearer_scheme)],
)
async def update_live_heart_rate_request_status(
    request_id: UUID,
    body: ReportLiveHeartRateRequestIn,
    patient: FromDishka[CurrentPatient],
    requests: FromDishka[LiveHeartRateRequests],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> PhoneLiveHeartRateRequestOut:
    try:
        request = await report_request_status(
            patient.id,
            LiveHeartRateRequestId(request_id),
            body.to_domain(),
            requests=requests,
            clock=clock,
            transaction=transaction,
        )
    except InvalidLiveHeartRateRequestTransition as error:
        raise ProblemError(
            status=409,
            code="invalid_live_heart_rate_request_transition",
            title="Недопустимое состояние запроса пульса",
            detail=str(error),
        ) from error
    if request is None:
        raise ProblemError(
            status=404,
            code="live_heart_rate_request_not_found",
            title="Запрос пульса не найден",
        )
    return PhoneLiveHeartRateRequestOut.from_domain(request)
