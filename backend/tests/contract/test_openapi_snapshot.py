"""Контракт с Android-приложением не меняется случайно (api-contract.md).

Изменили API осознанно — обновите снимок:
`uv run python -m kardiomonitor.app.export_openapi > tests/contract/openapi.json`
и прочитайте его дифф.
"""

from pathlib import Path

from kardiomonitor.app.export_openapi import openapi_json

SNAPSHOT = Path(__file__).with_name("openapi.json")


def test_схема_api_совпадает_с_зафиксированным_снимком() -> None:
    assert openapi_json() == SNAPSHOT.read_text(), (
        "OpenAPI изменился. Если это намеренно — обновите снимок командой из docstring модуля"
    )
