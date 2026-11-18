"""Сценарий: принять значение «пульса сейчас»."""

from kardiomonitor.features.monitoring.domain.live_heart_rate import (
    LiveHeartRateLog,
    LiveHeartRateReading,
)
from kardiomonitor.features.monitoring.domain.monitoring_events import (
    LiveHeartRateReceived,
    MonitoringSubscriber,
)
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.shared_kernel import Transaction


async def record_live_heart_rate(
    patient: Patient,
    reading: LiveHeartRateReading,
    *,
    log: LiveHeartRateLog,
    subscriber: MonitoringSubscriber,
    transaction: Transaction,
) -> None:
    """Идемпотентно по моменту замера; подписчик — в той же транзакции (ADR-0009)."""
    await log.record(patient.id, reading)
    await subscriber.live_heart_rate_received(LiveHeartRateReceived(patient, reading))
    await transaction.commit()
