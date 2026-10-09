#!/usr/bin/env bash
# Stop: не даёт агенту завершить ход, пока падают модульные тесты (в том числе
# архитектурные). Запускается, только если Kotlin/Gradle-файлы менялись после
# последнего зелёного прогона — ответы на вопросы не ждут сборку.
set -uo pipefail

input=$(cat)
# Второй заход после блокировки: не зацикливаться, агент уже получил вывод тестов.
if [[ $(jq -r '.stop_hook_active // false' <<<"$input") == "true" ]]; then
    exit 0
fi

cd "$CLAUDE_PROJECT_DIR" || exit 0
stamp=".gradle/claude-last-green-tests"
newer=()
[[ -f $stamp ]] && newer=(-newer "$stamp")
changed=$(find app build.gradle.kts settings.gradle.kts gradle -type f \
    \( -name '*.kt' -o -name '*.kts' -o -name '*.toml' -o -name '*.xml' \) \
    -not -path '*/build/*' "${newer[@]}" -print -quit)
[[ -z $changed ]] && exit 0

if output=$(./gradlew testDebugUnitTest --console=plain -q 2>&1); then
    mkdir -p .gradle && touch "$stamp"
    exit 0
fi
{
    echo "Модульные тесты падают — исправьте до завершения задачи (docs/testing.md)."
    echo "Отчёт: app/build/reports/tests/testDebugUnitTest/index.html"
    tail -n 60 <<<"$output"
} >&2
exit 2
