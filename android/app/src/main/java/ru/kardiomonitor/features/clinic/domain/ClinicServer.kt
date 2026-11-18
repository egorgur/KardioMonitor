package ru.kardiomonitor.features.clinic.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Сервер клиники на языке контекста. Реализация — HTTP (`clinic.data.HttpClinicServer`).
 * Сбои связи и отказы сервера — `java.io.IOException` с понятным сообщением.
 */
interface ClinicServer {
    suspend fun register(address: ServerAddress, name: PatientName, timeZone: ZoneId): ServerRegistration

    /** Обменивает код на токен; null — код неизвестен, уже использован или истёк. */
    suspend fun enroll(address: ServerAddress, code: EnrollmentCode): ServerRegistration?

    /** Начало последней минуты, которую сервер уже принял; null — ещё ни одной. */
    suspend fun lastUploadedMinute(registration: ServerRegistration): Instant?

    /** Идемпотентно: повтор пакета ничего не меняет на сервере. */
    suspend fun uploadMinutes(registration: ServerRegistration, minutes: List<MinuteReport>): UploadReceipt

    suspend fun sendLiveHeartRate(registration: ServerRegistration, report: LiveHeartRateReport)

    suspend fun rehabPlan(registration: ServerRegistration): PhoneRehabPlanReport

    suspend fun patientAlerts(registration: ServerRegistration, after: Long): PhoneAlertFeed

    /** null — клиника не ждёт непрерывного измерения от этого телефона. */
    suspend fun activeLiveHeartRateRequest(registration: ServerRegistration): PhoneLiveHeartRateRequest?

    suspend fun reportLiveHeartRateRequestStatus(
        registration: ServerRegistration,
        requestId: String,
        report: LiveHeartRateRequestStatusReport,
    ): PhoneLiveHeartRateRequest
}
