package ru.kardiomonitor.features.band.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.api.ConnectionState
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.domain.ConnectionStage

/** Состояние секции подключения. */
internal data class ConnectionUiState(
    val model: String = "Браслет",
    val title: String = "",
    val stage: ConnectionStage = ConnectionStage.Disconnected,
    val battery: String = "—",
    val isBackgroundSession: Boolean = false,
    val problem: String? = null,
) {
    val isConnected: Boolean get() = stage == ConnectionStage.Connected
    val isInProgress: Boolean get() = stage != ConnectionStage.Connected && stage != ConnectionStage.Disconnected
}

internal class ConnectionViewModel(
    private val connection: BandConnection,
    repository: BandRepository,
) : ViewModel() {
    val state: StateFlow<ConnectionUiState> = combine(repository.band, connection.state, ::toUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConnectionUiState())

    fun connect() = connection.connect()

    fun disconnect() = connection.disconnect()

    fun dismissProblem() = connection.dismissProblem()

    private fun toUiState(band: Band?, connectionState: ConnectionState) = ConnectionUiState(
        model = band?.credentials?.protocol?.label ?: "Браслет",
        title = "${band?.credentials?.address?.value.orEmpty()} · ${connectionState.stage.text()}",
        stage = connectionState.stage,
        battery = connectionState.battery?.let { "${it.percent} %" } ?: "—",
        isBackgroundSession = connectionState.isBackground && connectionState.stage != ConnectionStage.Disconnected,
        problem = connectionState.problem?.text(),
    )

    private fun ConnectionStage.text(): String = when (this) {
        ConnectionStage.Disconnected -> "Не подключён"
        ConnectionStage.Searching -> "Поиск браслета…"
        ConnectionStage.Connecting -> "Подключение…"
        ConnectionStage.Authenticating -> "Авторизация…"
        ConnectionStage.Connected -> "Подключён"
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
