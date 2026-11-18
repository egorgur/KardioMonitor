package ru.kardiomonitor.features.clinic.domain

/** Результат обмена одноразового кода на регистрацию телефона. */
sealed interface EnrollmentResult {
    data class Enrolled(val registration: ServerRegistration) : EnrollmentResult

    data object InvalidCode : EnrollmentResult

    data class Failed(val reason: String) : EnrollmentResult
}
