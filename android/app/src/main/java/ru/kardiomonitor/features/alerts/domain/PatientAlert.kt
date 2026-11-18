package ru.kardiomonitor.features.alerts.domain

import java.time.Instant
import java.time.LocalDate

/** Оповещение клиники, сохранённое на телефоне пациента. */
data class PatientAlert(
    val id: String,
    val kind: Kind,
    val day: LocalDate?,
    val createdAt: Instant,
    val details: Details,
) {
    init {
        require(id.isNotBlank()) { "идентификатор оповещения пуст" }
        require(matchesKind()) { "вид оповещения не совпадает со значениями" }
        require((kind == Kind.HighHeartRate) == (day == null)) { "день оповещения задан неверно" }
    }

    enum class Kind { HighHeartRate, DailyTargetMissed, DailyTargetMet, LowActivity, TrainingMinutesExceeded }

    sealed interface Details {
        data class HighHeartRate(
            val startedAt: Instant,
            val lastAt: Instant,
            val durationMinutes: Int,
            val maxHeartRate: Int,
            val thresholdBpm: Int,
        ) : Details {
            init {
                require(!lastAt.isBefore(startedAt)) { "конец эпизода раньше начала" }
                require(durationMinutes > 0) { "длительность эпизода должна быть положительной" }
                require(maxHeartRate > thresholdBpm) { "максимальный пульс должен быть выше порога" }
            }
        }

        data class DailyTarget(
            val trainingMinutes: Int,
            val targetTrainingMinutes: Int,
            val steps: Int,
            val targetSteps: Int,
        ) : Details {
            init {
                require(listOf(trainingMinutes, targetTrainingMinutes, steps, targetSteps).all { it >= 0 }) {
                    "значения дневной нормы не могут быть отрицательными"
                }
            }
        }

        /** Исторический снимок первого превышения; на телефоне заново не рассчитывается. */
        data class TrainingMinutesExceeded(val trainingMinutes: Int, val thresholdMinutes: Int) : Details {
            init {
                require(thresholdMinutes in 1..1440) { "порог тренировочных минут должен быть от 1 до 1440" }
                require(trainingMinutes > thresholdMinutes) { "тренировочные минуты должны превышать порог" }
            }
        }

        data class LowActivity(val steps: Int, val thresholdSteps: Int) : Details {
            init {
                require(steps >= 0 && thresholdSteps >= 0) { "значения активности не могут быть отрицательными" }
            }
        }
    }

    private fun matchesKind(): Boolean = when (kind) {
        Kind.HighHeartRate -> details is Details.HighHeartRate
        Kind.DailyTargetMissed, Kind.DailyTargetMet -> details is Details.DailyTarget
        Kind.LowActivity -> details is Details.LowActivity
        Kind.TrainingMinutesExceeded -> details is Details.TrainingMinutesExceeded
    }
}
