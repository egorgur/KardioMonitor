package ru.kardiomonitor.staff.shared

/** Shared protocol identity used by inventory, inspection and issuance without coupling their domains. */
enum class BandProtocol(val id: String, val displayName: String) {
    MiBand6Ble("mi-band6-ble", "Mi Smart Band 6 · BLE"),
    XiaomiBand9Spp("xiaomi-band9-spp", "Xiaomi Smart Band 9 · SPP"),
    Unknown("unknown", "Неизвестный протокол"),
    ;

    fun supports(model: String): Boolean = when (this) {
        MiBand6Ble -> BAND6_MODEL.matches(model)
        XiaomiBand9Spp -> model == "Xiaomi Smart Band 9" || model == "M2345B1"
        Unknown -> false
    }

    companion object {
        private val BAND6_MODEL = Regex("""(?i)^\s*(?:mi(?:\s+smart)?|xiaomi(?:\s+smart)?)\s+band\s+6\s*$""")
        fun fromId(id: String): BandProtocol = entries.firstOrNull { it.id == id } ?: Unknown
    }
}
