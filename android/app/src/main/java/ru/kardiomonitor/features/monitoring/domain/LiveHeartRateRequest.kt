package ru.kardiomonitor.features.monitoring.domain

import java.time.Instant

/** Устойчивое локальное состояние одного запроса клиники и упорядоченный outbox отчётов. */
data class LiveHeartRateRequest(
    val id: String,
    val durationMinutes: Int,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val status: Status = Status.WaitingForPhone,
    val startedAt: Instant? = null,
    val reports: List<Report> = emptyList(),
) {
    init {
        require(durationMinutes in 1..60) { "длительность запроса должна быть от 1 до 60 минут" }
    }

    fun withReport(report: Report, now: Instant): LiveHeartRateRequest {
        if (reports.lastOrNull() == report || (status == report.status && reports.isEmpty())) return this
        val start = if (report.status == Status.Running) startedAt ?: now else startedAt
        return copy(status = report.status, startedAt = start, reports = reports + report)
    }

    fun acknowledgeFirstReport(): LiveHeartRateRequest = copy(reports = reports.drop(1))

    fun remainingSeconds(now: Instant): Long {
        val start = startedAt ?: return durationMinutes * 60L
        return (durationMinutes * 60L - java.time.Duration.between(start, now).seconds)
            .coerceAtLeast(0)
    }

    enum class Status {
        WaitingForPhone,
        Running,
        Completed,
        StoppedByPatient,
        Failed,
    }

    enum class FailureReason {
        Expired,
        BandUnavailable,
        NotificationPermissionDenied,
        CompanionAssociationMissing,
        MeasurementFailed,
    }

    data class Report(val status: Status, val failureReason: FailureReason? = null) {
        init {
            require(status != Status.WaitingForPhone) { "телефон не может вернуть запрос в ожидание" }
            require((status == Status.Failed) == (failureReason != null)) {
                "причина обязательна только для failed"
            }
        }
    }
}
