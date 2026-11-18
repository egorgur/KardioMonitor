#!/usr/bin/env bash
# Stop: не даёт агенту завершить ход, пока падает полная проверка (ruff, mypy,
# import-linter, pytest). Запускается, только если Python-файлы или зависимости менялись
# после последнего зелёного прогона — ответы на вопросы не ждут проверку.
set -uo pipefail

input=$(cat)
# Второй заход после блокировки: не зацикливаться, агент уже получил вывод проверки.
if [[ $(jq -r '.stop_hook_active // false' <<<"$input") == "true" ]]; then
    exit 0
fi

cd "$CLAUDE_PROJECT_DIR" || exit 0
stamp=".pytest_cache/claude-last-green-check"
newer=()
[[ -f $stamp ]] && newer=(-newer "$stamp")
changed=$(find src tests scripts pyproject.toml uv.lock -type f \
    \( -name '*.py' -o -name 'pyproject.toml' -o -name 'uv.lock' -o -name '*.json' \) \
    -not -path '*/__pycache__/*' "${newer[@]}" -print -quit)
[[ -z $changed ]] && exit 0

if output=$(./scripts/check.sh 2>&1); then
    mkdir -p .pytest_cache && touch "$stamp"
    exit 0
fi
{
    echo "Полная проверка падает — исправьте до завершения задачи (docs/testing.md)."
    tail -n 60 <<<"$output"
} >&2
exit 2
