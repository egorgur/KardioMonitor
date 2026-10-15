package ru.kardiomonitor.features.monitoring.historysync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.monitoring.domain.LastSync
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Состояние секции синхронизации. */
internal data class HistorySyncUiState(
    val canSync: Boolean = false,
    val isConnected: Boolean = false,
    val progress: String? = null,
    val lastSync: String = "",
    val isBackgroundSyncEnabled: Boolean = true,
)

/** Синхронизация по кнопке и сразу после подключения по кнопке. */
internal class HistorySyncViewModel(
    connection: BandConnection,
    private val syncHistory: SyncHistory,
    private val settings: SyncSettings,
) : ViewModel() {
    private val run = MutableStateFlow(SyncRun())

    val state: StateFlow<HistorySyncUiState> =
        combine(connection.state, run, settings.lastSync, settings.isBackgroundSyncEnabled) { band, run, last, isEnabled ->
            HistorySyncUiState(
                canSync = band.isConnected && !run.isRunning,
                isConnected = band.isConnected,
                progress = run.progress,
                lastSync = "Последняя синхронизация: " + (last?.let(::format) ?: "не было"),
                isBackgroundSyncEnabled = isEnabled,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HistorySyncUiState())

    init {
        // Пользователь подключил браслет кнопкой — сразу забрать новые данные.
        viewModelScope.launch {
            connection.state
                .map { it.isConnected && !it.isBackground }
                .distinctUntilChanged()
                .drop(1)
                .filter { it }
                .collect { sync() }
        }
    }

    fun sync() {
        if (run.value.isRunning) return
        run.value = SyncRun(isRunning = true, progress = "Синхронизация…")
        viewModelScope.launch {
            val result = syncHistory(isBackground = false) { received ->
                run.update { it.copy(progress = "Синхронизация: получено $received мин") }
            }
            val failure = (result as? SyncResult.Failed)?.let { "Ошибка синхронизации: ${it.reason}" }
            run.value = SyncRun(progress = failure)
        }
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) = settings.setBackgroundSyncEnabled(enabled)

    private fun format(sync: LastSync): String {
        val time = sync.at.atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val day = when (time.toLocalDate()) {
            today -> ""
            today.minusDays(1) -> "вчера "
            else -> time.format(DateTimeFormatter.ofPattern("d MMM ", RUSSIAN))
        }
        val source = if (sync.isBackground) "в фоне" else "вручную"
        return "$day${time.format(DateTimeFormatter.ofPattern("HH:mm"))} ($source) — ${sync.summary}"
    }

    /** Синхронизация, запущенная с экрана: идёт ли и что показать. */
    private data class SyncRun(val isRunning: Boolean = false, val progress: String? = null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val RUSSIAN: Locale = Locale.forLanguageTag("ru")
    }
}
