"""Контракт API слайса «Вход врача» (api-contract.md)."""

from typing import Annotated, Self
from uuid import UUID

from pydantic import Field

from kardiomonitor.features.access.domain.clinician import Clinician
from kardiomonitor.features.access.domain.password import MAX_PASSWORD_LENGTH
from kardiomonitor.platform.web.api_model import ApiModel


class SignInIn(ApiModel):
    login: Annotated[str, Field(max_length=64)]
    password: Annotated[str, Field(max_length=MAX_PASSWORD_LENGTH)]

    def __repr__(self) -> str:
        return f"SignInIn(login={self.login!r}, password=***)"


class ClinicianOut(ApiModel):
    """Вошедший врач: для шапки панели."""

    clinician_id: UUID
    login: str
    name: str

    @classmethod
    def from_domain(cls, clinician: Clinician) -> Self:
        return cls(
            clinician_id=clinician.id, login=clinician.login.value, name=clinician.name.value
        )
