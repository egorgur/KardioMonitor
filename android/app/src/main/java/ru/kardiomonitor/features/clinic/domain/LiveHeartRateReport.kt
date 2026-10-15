package ru.kardiomonitor.features.clinic.domain

import java.time.Instant

/** Значение «пульса сейчас» для сервера. */
data class LiveHeartRateReport(val measuredAt: Instant, val bpm: Int)
