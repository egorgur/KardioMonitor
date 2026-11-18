#!/usr/bin/env bash
# PreToolUse(Bash): блокирует команды, которые уничтожают данные пациентов или раскрывают
# секреты. Код выхода 2 — команда не выполняется, причина передаётся агенту.
# Правила — CLAUDE.md, docs/ai-agent-workflow.md.
set -euo pipefail

command=$(jq -r '.tool_input.command // empty')

block() {
    echo "Заблокировано hook-ом guard-patient-data: $1" >&2
    exit 2
}

# Проверяется вызов команды, а не упоминание в тексте: сегмент — до |, ;, & или конца строки.
segment='[^|;&]*'
if grep -Eq "alembic${segment}[[:space:]]downgrade" <<<"$command"; then
    block "alembic downgrade удаляет данные пациентов. Нужна явная команда человека — пусть выполнит сам."
fi
if grep -Eiq "(^|[[:space:]\"'])(DROP[[:space:]]+(TABLE|DATABASE|SCHEMA)|TRUNCATE[[:space:]])" <<<"$command"; then
    block "DROP/TRUNCATE — только человеком и только на базе разработки."
fi
# Команда — в начале сегмента (после |, ;, & или с начала строки), а не внутри текста.
start='(^|[;&|])[[:space:]]*'
if grep -Eq "${start}psql[[:space:]]" <<<"$command" &&
    ! grep -Eq "psql${segment}(localhost|127\.0\.0\.1)" <<<"$command"; then
    block "psql только к базе разработки (localhost). К серверу с данными пациентов агент не подключается."
fi
if grep -Eq "${start}rm[[:space:]]${segment}kardio\.db" <<<"$command"; then
    block "kardio.db — база демо-сервера, на ней могут быть данные телефона. Спросите человека (README, «Важно»)."
fi
if grep -Eq "(cat|less|more|head|tail|bat|grep|source|\.)[[:space:]]${segment}\.env([[:space:]]|$)" <<<"$command"; then
    block ".env содержит настройки сервера и адрес базы — не выводить. Пример настроек — .env.example."
fi
if grep -Eq '\$\{?(KARDIO_DASHBOARD_KEY|KARDIO_DATABASE_URL)|printenv[^|;&]*KARDIO_' <<<"$command"; then
    block "секреты из окружения не выводить и не передавать в командах: сервер читает их сам."
fi
exit 0
