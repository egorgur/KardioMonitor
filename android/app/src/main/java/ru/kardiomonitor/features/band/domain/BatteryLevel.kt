package ru.kardiomonitor.features.band.domain

/** Заряд браслета, 0–100 %. */
@JvmInline
value class BatteryLevel(val percent: Int) {
    init {
        require(percent in 0..100) { "заряд вне диапазона 0–100 %: $percent" }
    }
}
