package ru.kardiomonitor

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kardiomonitor.data.BandConfig
import ru.kardiomonitor.data.BandSettings
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.DailySummary
import java.time.LocalDate
import java.time.ZoneId

data class UiState(
    val config: BandConfig? = null,
    val band: BandState = BandState(),
    val backgroundSync: Boolean = true,
    val day: LocalDate = LocalDate.now(),
    val dayMinutes: List<ActivityMinute> = emptyList(),
    val daySummary: DailySummary = DailySummary.of(emptyList()),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val manager = BandManager.get(app)

    private val _state = MutableStateFlow(
        UiState(config = BandConfig.load(app), backgroundSync = BandSettings.backgroundSyncEnabled(app))
    )
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            manager.state.collect { band -> _state.update { it.copy(band = band) } }
        }
        // новые данные в базе (синхронизация с экрана или в фоне) — перечитать выбранный день
        viewModelScope.launch {
            manager.state.distinctUntilChangedBy { it.dataVersion }.collect { loadDay(_state.value.day) }
        }
        scheduleBackgroundSync()
    }

    private fun scheduleBackgroundSync() {
        val s = _state.value
        if (s.config != null && s.backgroundSync) SyncWorker.schedule(context) else SyncWorker.cancel(context)
    }

    fun saveConfig(config: BandConfig) {
        BandConfig.save(context, config)
        _state.update { it.copy(config = config) }
        scheduleBackgroundSync()
    }

    fun forgetBand() {
        manager.forget()
        _state.update { it.copy(config = null) }
        scheduleBackgroundSync()
    }

    fun setBackgroundSync(enabled: Boolean) {
        BandSettings.setBackgroundSyncEnabled(context, enabled)
        _state.update { it.copy(backgroundSync = enabled) }
        scheduleBackgroundSync()
    }

    fun connect() {
        _state.value.config?.let(manager::connect)
    }

    fun disconnect() = manager.disconnect()
    fun sync() = manager.syncNow()
    fun toggleLiveHeartRate() = manager.toggleLiveHeartRate()
    fun setHeartRateInterval(minutes: Int) = manager.setHeartRateInterval(minutes)
    fun dismissError() = manager.dismissError()

    fun showDay(day: LocalDate) {
        if (day.isAfter(LocalDate.now())) return
        _state.update { it.copy(day = day) }
        loadDay(day)
    }

    private fun loadDay(day: LocalDate) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val from = day.atStartOfDay(zone).toEpochSecond()
            val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
            val minutes = withContext(Dispatchers.IO) { manager.store.query(from, to) }
            val summary = DailySummary.of(minutes)
            _state.update { if (it.day == day) it.copy(dayMinutes = minutes, daySummary = summary) else it }
        }
    }
}
