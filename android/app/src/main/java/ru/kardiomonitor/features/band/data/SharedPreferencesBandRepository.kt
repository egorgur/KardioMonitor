package ru.kardiomonitor.features.band.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.kardiomonitor.features.band.domain.AuthKey
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandAddress
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval

/**
 * Браслет в SharedPreferences (план — Keystore). Файлы и ключи — с прототипа, чтобы привязка
 * на телефоне пациента сохранилась: учётные данные в `band`, частота пульса в `band_settings`.
 */
class SharedPreferencesBandRepository(context: Context) : BandRepository {
    private val credentialsPrefs = context.getSharedPreferences(CREDENTIALS_PREFS, Context.MODE_PRIVATE)
    private val settingsPrefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(load())

    override val band: StateFlow<Band?> = current.asStateFlow()

    override fun save(band: Band) {
        credentialsPrefs.edit()
            .putString(MAC, band.credentials.address.value)
            .putString(KEY, band.credentials.key.hex)
            .apply()
        val interval = band.heartRateMeasurementInterval
        settingsPrefs.edit().apply {
            if (interval == null) remove(HEART_RATE_INTERVAL) else putInt(HEART_RATE_INTERVAL, interval.toMinutes())
        }.apply()
        current.value = band
    }

    override fun forget() {
        credentialsPrefs.edit().clear().apply()
        settingsPrefs.edit().remove(HEART_RATE_INTERVAL).apply()
        current.value = null
    }

    private fun load(): Band? {
        val mac = credentialsPrefs.getString(MAC, null) ?: return null
        val key = credentialsPrefs.getString(KEY, null) ?: return null
        val credentials = try {
            BandCredentials(BandAddress(mac), AuthKey(key))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "сохранённые учётные данные браслета повреждены — нужна новая привязка", e)
            return null
        }
        val interval = settingsPrefs.takeIf { it.contains(HEART_RATE_INTERVAL) }
            ?.getInt(HEART_RATE_INTERVAL, 0)
            ?.let(::intervalOf)
        return Band.restore(credentials, interval)
    }

    /** Минуты, 0 — выключено: так хранил прототип и так же команда браслету. */
    private fun HeartRateMeasurementInterval.toMinutes(): Int = when (this) {
        HeartRateMeasurementInterval.Off -> 0
        is HeartRateMeasurementInterval.Every -> minutes
    }

    private fun intervalOf(minutes: Int): HeartRateMeasurementInterval? = when (minutes) {
        0 -> HeartRateMeasurementInterval.Off
        in 1..HeartRateMeasurementInterval.MAX_MINUTES -> HeartRateMeasurementInterval.Every(minutes)
        else -> null
    }

    private companion object {
        const val TAG = "KardioBLE"
        const val CREDENTIALS_PREFS = "band"
        const val SETTINGS_PREFS = "band_settings"
        const val MAC = "mac"
        const val KEY = "key"
        const val HEART_RATE_INTERVAL = "hr_interval_minutes"
    }
}
