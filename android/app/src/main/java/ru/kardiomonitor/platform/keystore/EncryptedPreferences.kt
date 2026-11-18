package ru.kardiomonitor.platform.keystore

import android.content.SharedPreferences
import java.nio.charset.StandardCharsets

/** Шифрует отдельные значения SharedPreferences и переносит прежнее открытое значение при чтении. */
class EncryptedPreferences(
    private val preferences: SharedPreferences,
    private val secretBox: SecretBox,
) {
    fun get(encryptedName: String, legacyName: String): String? {
        val encoded = preferences.getString(encryptedName, null)
        if (encoded != null) return open(encoded)
        val legacy = preferences.getString(legacyName, null) ?: return null
        put(encryptedName, legacyName, legacy)
        return legacy
    }

    fun put(encryptedName: String, legacyName: String, value: String) {
        preferences.edit().putString(encryptedName, seal(value)).remove(legacyName).apply()
    }

    fun seal(value: String): String = secretBox.seal(value.toByteArray(StandardCharsets.UTF_8)).encode()

    fun open(encoded: String): String =
        secretBox.open(SealedSecret.decode(encoded)).toString(StandardCharsets.UTF_8)

    fun remove(encryptedName: String, legacyName: String) {
        preferences.edit().remove(encryptedName).remove(legacyName).apply()
    }
}
