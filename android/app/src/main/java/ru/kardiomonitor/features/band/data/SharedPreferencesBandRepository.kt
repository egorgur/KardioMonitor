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
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.platform.keystore.EncryptedPreferences
import ru.kardiomonitor.platform.keystore.SecretBox
import java.io.IOException
import java.time.Instant

/**
 * Браслет в SharedPreferences; ключ зашифрован через Android Keystore. Имена файлов и
 * открытых полей — с прототипа, чтобы привязка сохранилась. Открытый ключ переносится
 * в зашифрованное поле при первом чтении. У старой привязки [Band.pairedAt] = null.
 */
class SharedPreferencesBandRepository(context: Context, secretBox: SecretBox) : BandRepository {
    private val credentialsPrefs = context.getSharedPreferences(CREDENTIALS_PREFS, Context.MODE_PRIVATE)
    private val settingsPrefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private val encryptedCredentials = EncryptedPreferences(credentialsPrefs, secretBox)
    private val current = MutableStateFlow(load())

    override val band: StateFlow<Band?> = current.asStateFlow()

    override fun save(band: Band) {
        val encryptedKey = encryptedCredentials.seal(band.credentials.key.hex)
        val saved = credentialsPrefs.edit()
            .putString(MAC, band.credentials.address.value)
            .putString(PROTOCOL, band.credentials.protocol.wireName)
            .putString(ENCRYPTED_KEY, encryptedKey)
            .remove(KEY)
            .apply {
                val pairedAt = band.pairedAt
                if (pairedAt == null) remove(PAIRED_AT) else putLong(PAIRED_AT, pairedAt.toEpochMilli())
            }
            .commit()
        if (!saved) throw IOException("не удалось сохранить привязку браслета")
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
        val credentials = try {
            val protocol = BandProtocol.fromStored(credentialsPrefs.getString(PROTOCOL, null)) ?: return null
            val key = encryptedCredentials.get(ENCRYPTED_KEY, KEY) ?: return null
            BandCredentials(BandAddress(mac), AuthKey(key), protocol)
        } catch (e: Exception) {
            Log.w(TAG, "сохранённая привязка недоступна — нужна новая привязка")
            return null
        }
        val pairedAt = credentialsPrefs.takeIf { it.contains(PAIRED_AT) }
            ?.getLong(PAIRED_AT, 0)
            ?.let(Instant::ofEpochMilli)
        val interval = settingsPrefs.takeIf { it.contains(HEART_RATE_INTERVAL) }
            ?.getInt(HEART_RATE_INTERVAL, 0)
            ?.let(::intervalOf)
        return Band.restore(credentials, pairedAt, interval)
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
        const val PROTOCOL = "protocol"
        const val KEY = "key"
        const val ENCRYPTED_KEY = "key_sealed"
        const val PAIRED_AT = "paired_at_epoch_ms"
        const val HEART_RATE_INTERVAL = "hr_interval_minutes"
    }
}
