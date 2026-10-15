package ru.kardiomonitor.features.monitoring.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Значение «пульса сейчас» и момент, когда телефон его получил. */
data class LiveHeartRateReading(val measuredAt: Instant, val heartRate: HeartRate)

/**
 * Событие «пульс сейчас измерен»: слайс `liveheartrate` публикует значения, другие слайсы
 * (передача врачу) подписываются, не запуская второе измерение. Значения не хранятся:
 * опоздавший подписчик получает только новые.
 */
interface LiveHeartRateFeed {
    val readings: Flow<LiveHeartRateReading>

    fun publish(reading: LiveHeartRateReading)
}
