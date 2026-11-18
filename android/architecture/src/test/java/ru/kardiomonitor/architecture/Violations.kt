package ru.kardiomonitor.architecture

import org.junit.Assert.fail

internal fun assertNoViolations(rule: String, violations: List<String>) {
    if (violations.isEmpty()) return
    fail(
        buildString {
            appendLine("Нарушено правило: $rule")
            violations.forEach { appendLine("  - $it") }
        },
    )
}
