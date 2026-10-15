package ru.kardiomonitor.features.clinic.domain

/**
 * Адрес сервера клиники: `https://…` (для демо — адрес ngrok). Хранится без `/` в конце,
 * чтобы пути API приписывались одинаково.
 */
@JvmInline
value class ServerAddress private constructor(val url: String) {
    companion object {
        private val VALID = Regex("""^https?://[^\s/?#]+(/[^\s?#]*)?$""")

        /** Адрес из поля ввода: пробелы по краям и `/` в конце убираются, без схемы — https. */
        fun parse(input: String): ServerAddress {
            val trimmed = input.trim().trimEnd('/')
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            require(VALID.matches(withScheme)) { "неверный адрес сервера: $input" }
            return ServerAddress(withScheme)
        }
    }
}
