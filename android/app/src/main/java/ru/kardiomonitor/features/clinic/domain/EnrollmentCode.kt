package ru.kardiomonitor.features.clinic.domain

/** Одноразовый 22-символьный base64url-код привязки телефона, действующий 72 часа. */
@JvmInline
value class EnrollmentCode(val value: String) {
    init {
        require(VALID.matches(value)) { "неверный код привязки" }
    }

    override fun toString(): String = "EnrollmentCode(***)"

    private companion object {
        val VALID = Regex("[A-Za-z0-9_-]{22}")
    }
}
