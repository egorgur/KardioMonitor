package ru.kardiomonitor.features.clinic.domain

/** Телефон зарегистрирован на сервере клиники: куда отправлять и от чьего имени. */
data class ServerRegistration(
    val address: ServerAddress,
    val patientId: String,
    val patientName: PatientName,
    val token: UploadToken,
)
