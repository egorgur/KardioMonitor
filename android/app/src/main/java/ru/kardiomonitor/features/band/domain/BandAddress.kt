package ru.kardiomonitor.features.band.domain

/** Адрес браслета — MAC в формате `AA:BB:CC:DD:EE:FF`, верхний регистр. */
@JvmInline
value class BandAddress(val value: String) {
    init {
        require(FORMAT.matches(value)) { "адрес браслета должен быть вида AA:BB:CC:DD:EE:FF" }
    }

    private companion object {
        val FORMAT = Regex("""[0-9A-F]{2}(?::[0-9A-F]{2}){5}""")
    }
}
