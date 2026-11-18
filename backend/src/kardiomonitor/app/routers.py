"""Подключение слайсов. Новый слайс — строка здесь и в контрактах import-linter."""

from fastapi import FastAPI

from kardiomonitor.features.access import sign_in
from kardiomonitor.features.alerts import clinician_alerts, patient_alerts, unviewed_counts
from kardiomonitor.features.monitoring import (
    live_heart_rate,
    live_heart_rate_request,
    minute_upload,
    patient_day,
    patient_overview,
    upload_cursor,
)
from kardiomonitor.features.patients import admission, enrollment, patient_card, registration
from kardiomonitor.features.rehab import (
    day_progress,
    progress_overview,
    rehab_plan,
    rehab_progress,
    targets_for_phone,
)

SLICE_ROUTERS = (
    sign_in.router,
    registration.router,
    enrollment.router,
    admission.router,
    patient_card.router,
    minute_upload.router,
    upload_cursor.router,
    live_heart_rate.router,
    live_heart_rate_request.router,
    patient_overview.router,
    patient_day.router,
    rehab_plan.router,
    day_progress.router,
    rehab_progress.router,
    targets_for_phone.router,
    progress_overview.router,
    clinician_alerts.router,
    unviewed_counts.router,
    patient_alerts.router,
)


def include_slices(app: FastAPI) -> None:
    for router in SLICE_ROUTERS:
        app.include_router(router)
