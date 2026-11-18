package ru.kardiomonitor.features.clinic.domain

import java.time.Instant
import java.time.LocalDate

/** Оповещение в формате телефонного API; слайс alerts переводит его в свой домен. */
data class PhoneAlertReport(
    val alertId: String,
    val kind: Kind,
    val day: LocalDate?,
    val createdAt: Instant,
    val highHeartRate: HighHeartRate?,
    val dailyTarget: DailyTarget?,
    val lowActivity: LowActivity?,
    val trainingMinutesExceeded: TrainingMinutesExceeded? = null,
) {
    enum class Kind(val wireName: String) {
        HighHeartRate("high_heart_rate"),
        DailyTargetMissed("daily_target_missed"),
        DailyTargetMet("daily_target_met"),
        LowActivity("low_activity"),
        TrainingMinutesExceeded("training_minutes_exceeded"),
    }

    data class HighHeartRate(
        val startedAt: Instant,
        val lastAt: Instant,
        val durationMinutes: Int,
        val maxHeartRate: Int,
        val thresholdBpm: Int,
    )

    data class DailyTarget(
        val trainingMinutes: Int,
        val targetTrainingMinutes: Int,
        val steps: Int,
        val targetSteps: Int,
    )

    data class LowActivity(val steps: Int, val thresholdSteps: Int)

    data class TrainingMinutesExceeded(val trainingMinutes: Int, val thresholdMinutes: Int)
}
