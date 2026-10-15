package ru.kardiomonitor.features.clinic.domain

/**
 * Токен загрузки — секрет, который сервер выдал телефону при регистрации (`Authorization: Bearer`).
 * [toString] не раскрывает значение, токен не логируется.
 */
@JvmInline
value class UploadToken(val value: String) {
    init {
        require(value.isNotBlank()) { "пустой токен загрузки" }
    }

    override fun toString(): String = "UploadToken(***)"
}
