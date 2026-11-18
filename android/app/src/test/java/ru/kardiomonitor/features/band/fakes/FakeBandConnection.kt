package ru.kardiomonitor.features.band.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.api.ConnectionState
import ru.kardiomonitor.features.band.api.SessionUnavailableException
import ru.kardiomonitor.features.band.domain.ConnectionProblem

/** Сеанс связи без браслета: выполняет сценарий сразу или, если задан [unavailable], отказывает. */
class FakeBandConnection(var unavailable: ConnectionProblem? = null) : BandConnection {
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState())

    /** Режим каждого сеанса: true — фоновый. */
    val sessions: MutableList<Boolean> = mutableListOf()

    override fun connect() = Unit

    override fun disconnect() = Unit

    override fun closeIfIdle() = Unit

    override fun dismissProblem() = Unit

    override suspend fun <T> withSession(isBackground: Boolean, block: suspend () -> T): T {
        sessions += isBackground
        unavailable?.let { throw SessionUnavailableException(it, "браслет недоступен: $it") }
        return block()
    }

    override suspend fun <T> withSessionIf(
        isBackground: Boolean,
        isAllowed: () -> Boolean,
        block: suspend () -> T,
    ): T? = if (isAllowed()) withSession(isBackground, block) else null

    override suspend fun <T> withOpenSession(block: suspend () -> T): T {
        sessions += false
        unavailable?.let { throw SessionUnavailableException(it, "браслет недоступен: $it") }
        return block()
    }

    override suspend fun <T> holdOpen(block: suspend () -> T): T = block()
}
