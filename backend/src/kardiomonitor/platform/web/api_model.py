"""База схем запросов и ответов (coding-rules.md §10)."""

from datetime import UTC, datetime
from typing import Annotated

from pydantic import AfterValidator, AwareDatetime, BaseModel, ConfigDict


class ApiModel(BaseModel):
    """Схема API: неизвестное поле от клиента — 422, а не тихая потеря данных."""

    model_config = ConfigDict(extra="forbid", frozen=True, str_strip_whitespace=True)


def _to_utc(value: datetime) -> datetime:
    return value.astimezone(UTC)


UtcDatetime = Annotated[AwareDatetime, AfterValidator(_to_utc)]
"""Время в ответах — всегда UTC с `Z` (api-contract.md, «Общие правила»)."""
