package ru.kardiomonitor.features.clinic.domain

import java.time.LocalDate
import java.time.ZoneId

/** Текущая версия плана из телефонного API клиники, до перевода в модель реабилитации. */
data class PhoneRehabPlanReport(
    val timeZone: ZoneId,
    val effectiveFrom: LocalDate,
    val walkingStepsThreshold: Int,
    val startDate: LocalDate,
    val durationDays: Int,
    val stages: List<Stage>,
) {
    data class Stage(
        val firstDay: Int,
        val lastDay: Int,
        val lowerBpm: Int,
        val upperBpm: Int,
        val trainingMinutes: Int,
        val steps: Int,
    )
}
