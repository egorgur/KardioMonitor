package ru.kardiomonitor.features.band.domain

/**
 * Частота фонового измерения пульса, которое браслет пишет в историю. Браслет не сообщает
 * текущее значение — приложение хранит выбор и записывает его при каждом подключении.
 */
sealed interface HeartRateMeasurementInterval {

    /** Пульс в историю не записывается; «пульс сейчас» продолжает работать. */
    data object Off : HeartRateMeasurementInterval

    /** Измерение раз в [minutes] минут, 1–120. */
    @JvmInline
    value class Every(val minutes: Int) : HeartRateMeasurementInterval {
        init {
            require(minutes in 1..MAX_MINUTES) { "частота измерения пульса — от 1 до $MAX_MINUTES минут: $minutes" }
        }
    }

    companion object {
        const val MAX_MINUTES = 120

        /** Варианты в интерфейсе. */
        val CHOICES: List<HeartRateMeasurementInterval> = listOf(Every(1), Every(5), Every(10), Every(30), Off)
    }
}
