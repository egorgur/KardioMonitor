package ru.kardiomonitor.features.monitoring.data

import java.time.LocalDate
import java.time.ZoneId

/** Полуоткрытые границы пациентских суток; длина может быть 23 или 25 часов из-за DST. */
internal data class PatientDayRange(val fromEpochSecond: Long, val toEpochSecond: Long)

internal fun LocalDate.patientDayRange(timeZone: ZoneId): PatientDayRange = PatientDayRange(
    fromEpochSecond = atStartOfDay(timeZone).toEpochSecond(),
    toEpochSecond = plusDays(1).atStartOfDay(timeZone).toEpochSecond(),
)
