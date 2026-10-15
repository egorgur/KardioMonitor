#!/usr/bin/env bash
# Полная проверка бэкенда перед завершением задачи (docs/testing.md).
set -euo pipefail
cd "$(dirname "$0")/.."
uv run ruff format --check src tests scripts
uv run ruff check src tests scripts
uv run mypy src tests
uv run lint-imports
uv run pytest -q
