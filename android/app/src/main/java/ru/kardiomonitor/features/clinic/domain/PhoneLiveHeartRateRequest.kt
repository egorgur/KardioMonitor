package ru.kardiomonitor.features.clinic.domain

import java.time.Instant

/** Активный запрос клиники, полученный телефоном при синхронизации (ТР-09). */
data class PhoneLiveHeartRateRequest(
    val requestId: String,
    val durationMinutes: Int,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val status: Status,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val failureReason: FailureReason?,
) {
    init {
        require(durationMinutes in 1..60) { "длительность запроса должна быть от 1 до 60 минут" }
    }

    enum class Status(val wireName: String) {
        WaitingForPhone("waiting_for_phone"),
        Running("running"),
        Completed("completed"),
        StoppedByPatient("stopped_by_patient"),
        Failed("failed"),
    }

    enum class FailureReason(val wireName: String) {
        Expired("expired"),
        BandUnavailable("band_unavailable"),
        NotificationPermissionDenied("notification_permission_denied"),
        CompanionAssociationMissing("companion_association_missing"),
        MeasurementFailed("measurement_failed"),
    }
}
