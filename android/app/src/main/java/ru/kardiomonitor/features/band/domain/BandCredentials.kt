package ru.kardiomonitor.features.band.domain

/**
 * Учётные данные браслета: адрес и ключ. Выдаются клиникой в QR-коде `kardio;<MAC>;<ключ>`
 * или вводятся вручную. [toString] ключ не раскрывает.
 */
data class BandCredentials(val address: BandAddress, val key: AuthKey) {

    companion object {
        private val MAC = Regex("""(?i)\b([0-9a-f]{2}(?::[0-9a-f]{2}){5})\b""")
        private val KEY = Regex("""(?i)(?<![0-9a-f])([0-9a-f]{32})(?![0-9a-f])""")

        /** Достаёт адрес и ключ из любого текста: содержимого QR-кода, вставки из буфера и т. п. */
        fun parse(text: String): BandCredentials? {
            val mac = MAC.find(text)?.groupValues?.get(1) ?: return null
            val key = KEY.find(text)?.groupValues?.get(1) ?: return null
            return BandCredentials(BandAddress(mac.uppercase()), AuthKey(key.lowercase()))
        }
    }
}
