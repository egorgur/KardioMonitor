package ru.kardiomonitor.features.monitoring.domain

/** Интенсивность движения за минуту — сырое значение браслета 0–255. Смысл не расшифрован: не интерпретировать. */
@JvmInline
value class Intensity(val value: Int) {
    init {
        require(value in 0..255) { "интенсивность вне диапазона 0–255: $value" }
    }
}
