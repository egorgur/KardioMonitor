package ru.kardiomonitor.data

import android.content.Context

/** Итог последней синхронизации: время (epoch seconds), результат, запущена ли в фоне. */
data class LastSync(val at: Long, val summary: String, val background: Boolean)

/**
 * Настройки браслета и синхронизации, заданные из приложения. Настройки браслета прочитать
 * с него нельзя, поэтому храним здесь и повторно применяем при каждом подключении.
 */
object BandSettings {
    private const val PREFS = "band_settings"
    private const val HR_INTERVAL = "hr_interval_minutes"
    private const val BACKGROUND_SYNC = "background_sync"
    private const val LAST_SYNC_AT = "last_sync_at"
    private const val LAST_SYNC_SUMMARY = "last_sync_summary"
    private const val LAST_SYNC_BACKGROUND = "last_sync_background"

    /** Варианты частоты фонового пульса в минутах; 0 — выключено. */
    val HR_INTERVAL_OPTIONS = listOf(1, 5, 10, 30, 0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** null — из приложения не задавалась, действует настройка Mi Fitness. */
    fun heartRateInterval(context: Context): Int? =
        prefs(context).takeIf { it.contains(HR_INTERVAL) }?.getInt(HR_INTERVAL, 1)

    fun setHeartRateInterval(context: Context, minutes: Int) {
        prefs(context).edit().putInt(HR_INTERVAL, minutes).apply()
    }

    /** Фоновая синхронизация каждые 30 минут; по умолчанию включена. */
    fun backgroundSyncEnabled(context: Context) = prefs(context).getBoolean(BACKGROUND_SYNC, true)

    fun setBackgroundSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(BACKGROUND_SYNC, enabled).apply()
    }

    fun lastSync(context: Context): LastSync? {
        val p = prefs(context)
        if (!p.contains(LAST_SYNC_AT)) return null
        return LastSync(
            p.getLong(LAST_SYNC_AT, 0),
            p.getString(LAST_SYNC_SUMMARY, "") ?: "",
            p.getBoolean(LAST_SYNC_BACKGROUND, false),
        )
    }

    fun setLastSync(context: Context, sync: LastSync) {
        prefs(context).edit()
            .putLong(LAST_SYNC_AT, sync.at)
            .putString(LAST_SYNC_SUMMARY, sync.summary)
            .putBoolean(LAST_SYNC_BACKGROUND, sync.background)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
