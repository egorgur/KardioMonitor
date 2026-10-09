#!/usr/bin/env bash
# PreToolUse(Bash): блокирует команды, которые уничтожают данные на телефоне пациента
# или раскрывают ключ авторизации. Код выхода 2 — команда не выполняется, причина
# передаётся агенту. Правила — CLAUDE.md, docs/ai-agent-workflow.md.
set -euo pipefail

command=$(jq -r '.tool_input.command // empty')

block() {
    echo "Заблокировано hook-ом guard-patient-device: $1" >&2
    exit 2
}

# Проверяется вызов команды, а не упоминание в тексте: правка документации через
# heredoc не должна блокироваться. Сегмент команды — до |, ;, & или конца строки.
segment='[^|;&]*'
if grep -Eq "adb${segment}[[:space:]]pm[[:space:]]+clear" <<<"$command"; then
    block "pm clear стирает историю пульса на телефоне пациента. Нужна явная команда человека — пусть выполнит сам."
fi
if grep -Eq "adb${segment}[[:space:]]uninstall" <<<"$command"; then
    block "удаление приложения стирает базу на телефоне пациента. Для переустановки — ./gradlew installDebug."
fi
if grep -Eq "adb${segment}[[:space:]](rm|truncate)[[:space:]]${segment}(databases|shared_prefs)" <<<"$command"; then
    block "удаление базы или настроек на телефоне пациента запрещено (testing.md)."
fi
if grep -Eq '\$\{?MIBAND_AUTH_KEY|printenv[^|;&]*MIBAND_AUTH_KEY' <<<"$command"; then
    block "ключ авторизации браслета нельзя выводить или передавать в командах: скрипты читают MIBAND_AUTH_KEY сами."
fi
exit 0
