package ru.kardiomonitor.staff.features.inventory.domain

import ru.kardiomonitor.staff.shared.canonicalTextKey

@JvmInline
value class BandLabel private constructor(val value: String) {
    val comparisonKey: String get() = canonicalTextKey(value)

    companion object {
        fun of(value: String): BandLabel {
            val normalized = value.trim()
            require(normalized.length in 1..20) { "Метка должна содержать от 1 до 20 знаков" }
            return BandLabel(normalized)
        }
    }
}
