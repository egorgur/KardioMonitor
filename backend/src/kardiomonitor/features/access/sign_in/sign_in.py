"""Сценарий: врач входит на панель по логину и паролю."""

from kardiomonitor.features.access.domain.authentication import Authentication, SignedIn
from kardiomonitor.shared_kernel import Transaction


async def sign_in(
    login: str, password: str, *, authentication: Authentication, transaction: Transaction
) -> SignedIn:
    """Новый сеанс врача. Счётчик неудач фиксируется и при отказе — иначе он откатился бы вместе
    с запросом и блокировка не сработала бы; сеанс — только после фиксации."""
    try:
        clinician = await authentication.authenticate(login, password)
    finally:
        await transaction.commit()
    return await authentication.start_session(clinician)
