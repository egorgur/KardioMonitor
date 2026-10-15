package ru.kardiomonitor.features.monitoring.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.monitoring.domain.LastSync
import ru.kardiomonitor.features.monitoring.domain.SyncSettings

/** Настройки синхронизации в памяти. */
class FakeSyncSettings : SyncSettings {
    private val backgroundSync = MutableStateFlow(true)
    private val last = MutableStateFlow<LastSync?>(null)

    override val isBackgroundSyncEnabled: StateFlow<Boolean> = backgroundSync
    override val lastSync: StateFlow<LastSync?> = last

    override fun setBackgroundSyncEnabled(enabled: Boolean) {
        backgroundSync.value = enabled
    }

    override fun recordSync(sync: LastSync) {
        last.value = sync
    }

    override fun reset() {
        backgroundSync.value = true
        last.value = null
    }
}
