"""Печать OpenAPI в stdout — для снимка `tests/contract/openapi.json` (api-contract.md)."""

import json
import sys
from pathlib import Path

from kardiomonitor.app.main import create_app
from kardiomonitor.app.settings import Settings


def openapi_json() -> str:
    """Схема API без зависимости от собранной панели и локальной базы."""
    settings = Settings(
        database_url="sqlite+aiosqlite:///:memory:", frontend_dist=Path("/nonexistent")
    )
    app = create_app(settings)
    return json.dumps(app.openapi(), ensure_ascii=False, indent=2, sort_keys=True) + "\n"


if __name__ == "__main__":
    sys.stdout.write(openapi_json())
