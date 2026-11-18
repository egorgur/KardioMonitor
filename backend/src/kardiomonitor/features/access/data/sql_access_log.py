"""Журнал доступа в базе."""

from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.access.data.tables import AccessRecordRow
from kardiomonitor.features.access.domain.access_log import AccessAction
from kardiomonitor.features.access.domain.clinician import ClinicianId
from kardiomonitor.shared_kernel import Clock


class SqlAccessLog:
    """Реализация `AccessLog`: запись в транзакции запроса, фиксирует сценарий (ADR-0005)."""

    def __init__(self, session: AsyncSession, clock: Clock) -> None:
        self._session = session
        self._clock = clock

    async def record(
        self, clinician_id: ClinicianId, action: AccessAction, subject_id: UUID
    ) -> None:
        self._session.add(
            AccessRecordRow(
                clinician_id=clinician_id,
                action=action.value,
                subject_id=subject_id,
                at=self._clock.now(),
            )
        )
        await self._session.flush()
