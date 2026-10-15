package ru.kardiomonitor.features.monitoring.domain

/**
 * Итоги дня по минутам активности одного дня. Создаётся [DailySummary.of].
 *
 * @property recordedMinutes сколько минут дня записал браслет.
 * @property heartRateCoveragePercent доля записанных минут с замером пульса, 0–100 %.
 * @property notWornMinutes минуты, когда браслет снят или заряжается.
 */
data class DailySummary(
    val recordedMinutes: Int,
    val steps: StepCount,
    val averageHeartRate: HeartRate?,
    val minHeartRate: HeartRate?,
    val maxHeartRate: HeartRate?,
    val heartRateCoveragePercent: Int,
    val notWornMinutes: Int,
) {
    val hasData: Boolean get() = recordedMinutes > 0

    companion object {
        fun of(minutes: List<ActivityMinute>): DailySummary {
            val heartRates = minutes.mapNotNull { it.heartRate?.bpm }
            return DailySummary(
                recordedMinutes = minutes.size,
                steps = minutes.fold(StepCount.ZERO) { total, minute -> total + minute.steps },
                // среднее округляется вниз, как показывал прототип
                averageHeartRate = heartRates.takeIf { it.isNotEmpty() }?.let { HeartRate(it.average().toInt()) },
                minHeartRate = heartRates.minOrNull()?.let(::HeartRate),
                maxHeartRate = heartRates.maxOrNull()?.let(::HeartRate),
                heartRateCoveragePercent = if (minutes.isEmpty()) 0 else 100 * heartRates.size / minutes.size,
                notWornMinutes = minutes.count { !it.isWorn },
            )
        }
    }
}
