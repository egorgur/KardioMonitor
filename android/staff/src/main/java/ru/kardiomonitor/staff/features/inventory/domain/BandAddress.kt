package ru.kardiomonitor.staff.features.inventory.domain

@JvmInline
value class BandAddress private constructor(val value: String) {
    companion object {
        private val EXACT = Regex("""[0-9A-F]{2}(?::[0-9A-F]{2}){5}""")
        private val ANYWHERE = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{2}(?::[0-9a-f]{2}){5}(?![0-9a-f])""")

        fun of(value: String): BandAddress {
            val normalized = value.trim().uppercase()
            require(EXACT.matches(normalized)) { "Некорректный MAC-адрес браслета" }
            return BandAddress(normalized)
        }

        fun findIn(text: String): BandAddress? = ANYWHERE.find(text)?.value?.let(::of)
    }
}
