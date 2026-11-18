package ru.kardiomonitor.features.monitoring.domain

import kotlinx.coroutines.flow.StateFlow

/** Устойчивое состояние запроса одного зарегистрированного пациента телефона. */
interface LiveHeartRateRequestRepository {
    val request: StateFlow<LiveHeartRateRequest?>

    fun selectPatient(patientId: String?)

    fun save(patientId: String, request: LiveHeartRateRequest)
}
