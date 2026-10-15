"""Подключение слайсов. Новый слайс — строка здесь и в контрактах import-linter."""

from fastapi import FastAPI

from kardiomonitor.features.monitoring import (
    live_heart_rate,
    minute_upload,
    patient_day,
    patient_overview,
    upload_cursor,
)
from kardiomonitor.features.patients import registration

SLICE_ROUTERS = (
    registration.router,
    minute_upload.router,
    upload_cursor.router,
    live_heart_rate.router,
    patient_overview.router,
    patient_day.router,
)


def include_slices(app: FastAPI) -> None:
    for router in SLICE_ROUTERS:
        app.include_router(router)
