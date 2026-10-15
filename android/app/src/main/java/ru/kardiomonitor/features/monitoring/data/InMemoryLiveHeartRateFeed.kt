package ru.kardiomonitor.features.monitoring.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateFeed
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateReading

/**
 * Поток «пульса сейчас» в памяти процесса. Медленный подписчик (сеть) теряет старые значения,
 * а не задерживает измерение: важен свежий пульс, а не каждый.
 */
class InMemoryLiveHeartRateFeed : LiveHeartRateFeed {
    private val events = MutableSharedFlow<LiveHeartRateReading>(
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val readings: Flow<LiveHeartRateReading> = events.asSharedFlow()

    override fun publish(reading: LiveHeartRateReading) {
        events.tryEmit(reading)
    }

    private companion object {
        const val BUFFER = 8
    }
}
