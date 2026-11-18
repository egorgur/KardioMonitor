package ru.kardiomonitor.features.reminders.domain

import java.time.Instant

/** ACL-снимок минуты мониторинга для локальных условий напоминаний. */
data class ReminderActivityMinute(
    val start: Instant,
    val steps: Int,
    val state: State,
) {
    init {
        require(steps >= 0) { "число шагов не может быть отрицательным" }
    }

    enum class State { Worn, NotWorn, Charging, Sleep }
}
