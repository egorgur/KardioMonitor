"""Точка входа слайса: один запуск периодической задачи — своя область dishka и транзакция."""

from dishka import AsyncContainer

from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans
from kardiomonitor.shared_kernel import Clock, Transaction

from .check_daily_alerts import check_daily_alerts


async def run_daily_check(container: AsyncContainer) -> None:
    """Проверить всех пациентов; запускается раз в минуту (`platform.periodic`)."""
    async with container() as request:
        await check_daily_alerts(
            registry=await request.get(PatientRegistry),
            plans=await request.get(RehabPlans),
            history=await request.get(ActivityHistory),
            journal=await request.get(AlertJournal),
            clock=await request.get(Clock),
            transaction=await request.get(Transaction),
        )
