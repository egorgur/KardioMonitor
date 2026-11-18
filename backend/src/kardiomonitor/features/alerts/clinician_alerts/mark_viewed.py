"""Сценарий: врач отмечает оповещение «просмотрено»."""

from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.alerts.domain.patient_alert import AlertId
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.shared_kernel import Clock, Transaction


async def mark_viewed(
    patient: Patient,
    alert_id: AlertId,
    *,
    journal: AlertJournal,
    clock: Clock,
    transaction: Transaction,
) -> bool:
    """Идемпотентно: повторная отметка не меняет время. False — у пациента нет оповещения."""
    if not await journal.mark_viewed(patient.id, alert_id, clock.now()):
        return False
    await transaction.commit()
    return True
