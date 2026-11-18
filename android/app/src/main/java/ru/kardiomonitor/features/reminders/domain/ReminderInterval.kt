package ru.kardiomonitor.features.reminders.domain

/** Разрешённая пациенту частота интервальных напоминаний. */
enum class ReminderInterval(val minutes: Long) {
    HalfHour(30),
    OneHour(60),
    OneAndHalfHours(90),
    TwoHours(120),
    ThreeHours(180),
}
