package ru.kardiomonitor.features.clinic.domain

import java.time.Instant

/**
 * Минута активности в том виде, в каком её принимает сервер (API v1, backend/docs/api-contract.md).
 * [kindCode] — исходный код браслета, только для [ReportedKind.Other]: остальные сервер знает по названию.
 */
data class MinuteReport(
    val start: Instant,
    val steps: Int,
    val heartRateBpm: Int?,
    val kind: ReportedKind,
    val kindCode: Int?,
    val intensity: Int,
) {
    /** Тип минуты для сервера. */
    enum class ReportedKind { NotWorn, Charging, Sleep, Other }
}
