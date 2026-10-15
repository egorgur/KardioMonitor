package ru.kardiomonitor.features.band.domain

/**
 * Ключ авторизации браслета — 16 байт, [hex] — 32 hex-символа в нижнем регистре.
 * **Секрет:** [toString] значение не раскрывает; ключ не логировать.
 */
@JvmInline
value class AuthKey(val hex: String) {
    init {
        require(FORMAT.matches(hex)) { "ключ авторизации должен состоять из 32 hex-символов в нижнем регистре" }
    }

    fun bytes(): ByteArray = ByteArray(KEY_BYTES) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    override fun toString(): String = "AuthKey(скрыт)"

    private companion object {
        const val KEY_BYTES = 16
        val FORMAT = Regex("""[0-9a-f]{32}""")
    }
}
