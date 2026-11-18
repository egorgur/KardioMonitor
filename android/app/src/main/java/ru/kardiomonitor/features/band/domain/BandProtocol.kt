package ru.kardiomonitor.features.band.domain

/** Протокол выбирается при выдаче, а не угадывается по имени или ключу. */
enum class BandProtocol(val wireName: String, val label: String) {
    MiBand6Ble("mi-band6-ble", "Mi Smart Band 6 · BLE"),
    XiaomiBand9Spp("xiaomi-band9-spp", "Smart Band 9 · SPP (ограниченная поддержка)");

    val canWriteMeasurementInterval: Boolean get() = this == MiBand6Ble
    val canMonitorActivity: Boolean get() = this == MiBand6Ble

    companion object {
        fun fromWireName(value: String): BandProtocol? = entries.find { it.wireName == value }

        /** Отсутствующее поле относится только к старой привязке Band 6. Неизвестное — ошибка. */
        fun fromStored(value: String?): BandProtocol? =
            if (value == null) MiBand6Ble else fromWireName(value)
    }
}
