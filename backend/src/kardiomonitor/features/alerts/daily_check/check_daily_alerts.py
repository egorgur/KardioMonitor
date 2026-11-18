"""Сценарий: дневные оповещения пациентов, у которых наступило время проверки."""

from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.alerts.domain.daily_check import DailyCheck
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans, plan_of
from kardiomonitor.shared_kernel import Clock, Transaction


async def check_daily_alerts(
    *,
    registry: PatientRegistry,
    plans: RehabPlans,
    history: ActivityHistory,
    journal: AlertJournal,
    clock: Clock,
    transaction: Transaction,
) -> int:
    """Идемпотентно: день пациента проверяется один раз — повтор, перезапуск сервера и второй
    процесс новых оповещений не создают (ADR-0009). Поздние минуты дня проверку не повторяют
    (ТР-08, п. 3). Каждый пациент фиксируется отдельно. Возвращает число новых оповещений.
    """
    now = clock.now()
    created = 0
    for patient in await registry.all_patients():
        plan = await plan_of(patient, plans)
        check = DailyCheck.due(patient, plan, now=now)
        if check is None or await journal.has_daily_alerts(patient.id, check.day):
            continue
        minutes = await history.minutes_between(patient.id, *check.period)
        alerts = check.alerts(plan, minutes, now=now)
        for alert in alerts:
            await journal.save(alert)
        await transaction.commit()
        created += len(alerts)
    return created
