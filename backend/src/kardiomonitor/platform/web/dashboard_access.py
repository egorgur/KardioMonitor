"""Доступ к эндпоинтам панели клиники.

Панель открыта через ngrok всему интернету, а в ней имена и пульс. Если задан ключ
(`KARDIO_DASHBOARD_KEY`), эндпоинты панели требуют заголовок `X-Dashboard-Key`.
Это защита демо-прототипа, а не вход сотрудников (контекст «Доступ», план).
"""

import secrets
from dataclasses import dataclass, field
from typing import Annotated

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import Header

from kardiomonitor.platform.web.problem import ProblemError


@dataclass(frozen=True, slots=True)
class DashboardAccessPolicy:
    """Ключ панели; None — панель открыта."""

    key: str | None = field(default=None, repr=False)

    def allows(self, presented_key: str | None) -> bool:
        if self.key is None:
            return True
        return presented_key is not None and secrets.compare_digest(presented_key, self.key)


@inject
async def require_dashboard_access(
    policy: FromDishka[DashboardAccessPolicy],
    dashboard_key: Annotated[str | None, Header(alias="X-Dashboard-Key")] = None,
) -> None:
    """Зависимость роутеров панели: без верного ключа — 401."""
    if not policy.allows(dashboard_key):
        raise ProblemError(
            status=401,
            code="dashboard_key_required",
            title="Нужен ключ панели",
            detail="Откройте панель по ссылке с параметром ?key=…",
        )
