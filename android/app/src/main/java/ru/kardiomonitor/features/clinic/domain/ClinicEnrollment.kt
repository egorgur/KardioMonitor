package ru.kardiomonitor.features.clinic.domain

/** Привязка телефона к пациенту, которого заранее создала клиника. */
fun interface ClinicEnrollment {
    suspend fun enroll(address: ServerAddress, code: EnrollmentCode): EnrollmentResult
}
