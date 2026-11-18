package ru.kardiomonitor.platform.keystore

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class EncryptedPreferencesTest {
    @Test
    fun `открытый секрет переносится в зашифрованное поле без потери значения`() {
        val preferences = MemoryPreferences()
        preferences.edit().putString("legacy", "patient-secret").commit()
        val encrypted = EncryptedPreferences(preferences, XorSecretBox())

        assertEquals("patient-secret", encrypted.get("sealed", "legacy"))
        assertFalse(preferences.contains("legacy"))
        assertNotNull(preferences.getString("sealed", null))
        assertFalse(preferences.getString("sealed", null).orEmpty().contains("patient-secret"))
    }

    @Test
    fun `повторное чтение использует зашифрованное поле`() {
        val preferences = MemoryPreferences()
        val encrypted = EncryptedPreferences(preferences, XorSecretBox())
        encrypted.put("sealed", "legacy", "patient-secret")

        assertEquals("patient-secret", encrypted.get("sealed", "legacy"))
    }
}

private class XorSecretBox : SecretBox {
    override fun seal(plainText: ByteArray): SealedSecret =
        SealedSecret(ByteArray(12), plainText.map { (it.toInt() xor MASK).toByte() }.toByteArray())

    override fun open(secret: SealedSecret): ByteArray =
        secret.cipherText.map { (it.toInt() xor MASK).toByte() }.toByteArray()

    private companion object {
        const val MASK = 0x5A
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String?, default: String?): String? = values[key] as? String ?: default
    override fun getStringSet(key: String?, default: Set<String>?): Set<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>) ?: default
    override fun getInt(key: String?, default: Int): Int = values[key] as? Int ?: default
    override fun getLong(key: String?, default: Long): Long = values[key] as? Long ?: default
    override fun getFloat(key: String?, default: Float): Float = values[key] as? Float ?: default
    override fun getBoolean(key: String?, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = MemoryEditor(values)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}

private class MemoryEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
    private val changes = mutableMapOf<String, Any?>()
    private val removals = mutableSetOf<String>()
    private var shouldClear = false

    override fun putString(key: String?, value: String?): SharedPreferences.Editor = change(key, value)
    override fun putStringSet(key: String?, value: Set<String>?): SharedPreferences.Editor = change(key, value)
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = change(key, value)
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = change(key, value)
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = change(key, value)
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = change(key, value)

    override fun remove(key: String?): SharedPreferences.Editor = apply {
        if (key != null) removals += key
    }

    override fun clear(): SharedPreferences.Editor = apply { shouldClear = true }

    override fun commit(): Boolean {
        applyChanges()
        return true
    }

    override fun apply() = applyChanges()

    private fun change(key: String?, value: Any?): SharedPreferences.Editor = apply {
        if (key != null) changes[key] = value
    }

    private fun applyChanges() {
        if (shouldClear) values.clear()
        removals.forEach(values::remove)
        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
    }
}
