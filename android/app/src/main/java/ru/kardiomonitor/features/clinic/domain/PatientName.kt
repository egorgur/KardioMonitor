package ru.kardiomonitor.features.clinic.domain

/** Как показать пациента врачу на демонстрации: 1–100 знаков, без пробелов по краям. */
@JvmInline
value class PatientName(val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim()) { "имя пациента пустое" }
        require(value.length <= MAX_LENGTH) { "имя пациента длиннее $MAX_LENGTH знаков" }
    }

    companion object {
        const val MAX_LENGTH = 100
    }
}
