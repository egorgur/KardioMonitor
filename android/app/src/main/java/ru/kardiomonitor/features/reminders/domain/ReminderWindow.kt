package ru.kardiomonitor.features.reminders.domain

import java.time.Duration
import java.time.LocalTime

/** Дневное окно по местному времени телефона; через полночь не переходит. */
data class ReminderWindow(val start: LocalTime, val end: LocalTime) {
    init {
        require(end.isAfter(start)) { "конец окна должен быть позже начала" }
        require(Duration.between(start, end).toMinutes() >= 60) { "окно напоминаний не короче часа" }
    }

    fun contains(time: LocalTime): Boolean = !time.isBefore(start) && time.isBefore(end)
}
