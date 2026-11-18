package ru.kardiomonitor.features.band.domain

/**
 * Учётные данные: адрес, ключ и явный протокол. QR v3 или ручной ввод;
 * legacy QR/привязка без протокола — только Band 6. [toString] ключ не раскрывает.
 */
data class BandCredentials(
    val address: BandAddress,
    val key: AuthKey,
    val protocol: BandProtocol = BandProtocol.MiBand6Ble,
) {

    companion object {
        private val MAC = Regex("""(?i)\b([0-9a-f]{2}(?::[0-9a-f]{2}){5})\b""")
        private val KEY = Regex("""(?i)(?<![0-9a-f])([0-9a-f]{32})(?![0-9a-f])""")

        /** v3 разбирается строго; прежний свободный текст относится только к Band 6. */
        fun parse(text: String): BandCredentials? {
            val trimmed = text.trim()
            if (trimmed.startsWith("kardio3")) {
                if (MAC.containsMatchIn(trimmed) || KEY.containsMatchIn(trimmed)) return null
                return PairingPayloadV3.parse(trimmed)
            }
            if (Regex("(?i)kardio[0-9]+").containsMatchIn(trimmed)) return null
            if (trimmed.split(';').any { it.startsWith("p=") || it.startsWith("protocol=") }) return null
            val mac = MAC.find(trimmed)?.groupValues?.get(1) ?: return null
            val key = KEY.find(trimmed)?.groupValues?.get(1) ?: return null
            return BandCredentials(BandAddress(mac.uppercase()), AuthKey(key.lowercase()))
        }
    }
}
