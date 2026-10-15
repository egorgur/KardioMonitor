package ru.kardiomonitor.features.monitoring.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.kardiomonitor.features.monitoring.domain.LastSync
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import java.time.Instant

/**
 * Настройки синхронизации в SharedPreferences. Файл `band_settings` и ключи — с прототипа:
 * настройки на телефоне пациента сохраняются. В том же файле контекст «Браслет» хранит свои ключи.
 */
class SharedPreferencesSyncSettings(context: Context) : SyncSettings {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val backgroundSync = MutableStateFlow(prefs.getBoolean(BACKGROUND_SYNC, true))
    private val last = MutableStateFlow(loadLastSync())

    override val isBackgroundSyncEnabled: StateFlow<Boolean> = backgroundSync.asStateFlow()
    override val lastSync: StateFlow<LastSync?> = last.asStateFlow()

    override fun setBackgroundSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(BACKGROUND_SYNC, enabled).apply()
        backgroundSync.value = enabled
    }

    override fun recordSync(sync: LastSync) {
        prefs.edit()
            .putLong(LAST_SYNC_AT, sync.at.epochSecond)
            .putString(LAST_SYNC_SUMMARY, sync.summary)
            .putBoolean(LAST_SYNC_BACKGROUND, sync.isBackground)
            .apply()
        last.value = sync
    }

    override fun reset() {
        prefs.edit()
            .remove(BACKGROUND_SYNC)
            .remove(LAST_SYNC_AT)
            .remove(LAST_SYNC_SUMMARY)
            .remove(LAST_SYNC_BACKGROUND)
            .apply()
        backgroundSync.value = true
        last.value = null
    }

    private fun loadLastSync(): LastSync? {
        if (!prefs.contains(LAST_SYNC_AT)) return null
        return LastSync(
            at = Instant.ofEpochSecond(prefs.getLong(LAST_SYNC_AT, 0)),
            summary = prefs.getString(LAST_SYNC_SUMMARY, null).orEmpty(),
            isBackground = prefs.getBoolean(LAST_SYNC_BACKGROUND, false),
        )
    }

    private companion object {
        const val PREFS = "band_settings"
        const val BACKGROUND_SYNC = "background_sync"
        const val LAST_SYNC_AT = "last_sync_at"
        const val LAST_SYNC_SUMMARY = "last_sync_summary"
        const val LAST_SYNC_BACKGROUND = "last_sync_background"
    }
}
