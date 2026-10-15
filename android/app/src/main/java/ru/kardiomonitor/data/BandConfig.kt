package ru.kardiomonitor.data

import android.content.Context
import ru.kardiomonitor.platform.ble.hexToBytes

/** MAC-адрес и ключ браслета. Выдаются клиникой (QR-код) или вводятся вручную. */
data class BandConfig(val mac: String, val keyHex: String) {
    val key: ByteArray get() = keyHex.hexToBytes()

    companion object {
        private val MAC_RE = Regex("""(?i)\b([0-9a-f]{2}(?::[0-9a-f]{2}){5})\b""")
        private val KEY_RE = Regex("""(?i)(?<![0-9a-f])([0-9a-f]{32})(?![0-9a-f])""")

        /** Достаёт MAC и ключ из любого текста: содержимого QR-кода, вставки из буфера и т. п. */
        fun parse(text: String): BandConfig? {
            val mac = MAC_RE.find(text)?.groupValues?.get(1) ?: return null
            val key = KEY_RE.find(text)?.groupValues?.get(1) ?: return null
            return BandConfig(mac.uppercase(), key.lowercase())
        }

        fun load(context: Context): BandConfig? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mac = prefs.getString("mac", null) ?: return null
            val key = prefs.getString("key", null) ?: return null
            return BandConfig(mac, key)
        }

        fun save(context: Context, config: BandConfig?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (config == null) clear() else putString("mac", config.mac).putString("key", config.keyHex)
            }.apply()
        }

        private const val PREFS = "band"
    }
}
