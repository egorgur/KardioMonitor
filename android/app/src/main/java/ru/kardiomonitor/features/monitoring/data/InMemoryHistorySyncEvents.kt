package ru.kardiomonitor.features.monitoring.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import ru.kardiomonitor.features.monitoring.domain.HistorySyncEvents

/** События попыток синхронизации внутри одного процесса. */
class InMemoryHistorySyncEvents : HistorySyncEvents {
    private val mutableEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val attemptsCompleted: Flow<Unit> = mutableEvents

    override fun publishAttemptCompleted() {
        mutableEvents.tryEmit(Unit)
    }
}
