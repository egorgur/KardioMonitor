package ru.kardiomonitor.architecture

import org.junit.Assert.fail

/** Падает со списком нарушений и ссылкой на правило, чтобы агент сразу знал, что читать. */
internal fun assertNoViolations(rule: String, violations: List<String>) {
    if (violations.isEmpty()) return
    fail(
        buildString {
            appendLine("Нарушено правило: $rule")
            violations.forEach { appendLine("  - $it") }
        },
    )
}
