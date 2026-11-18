package ru.kardiomonitor.features.alerts.domain

import kotlinx.coroutines.flow.StateFlow

/** Локальный журнал оповещений выбранного пациента и курсор серверной ленты. */
interface PatientAlertRepository {
    val alerts: StateFlow<List<PatientAlert>>

    fun selectPatient(patientId: String?)

    fun cursor(patientId: String): Long

    /** Сохраняет страницу и возвращает только впервые увиденные оповещения. */
    fun save(patientId: String, page: List<PatientAlert>, cursor: Long): List<PatientAlert>

    fun wasNotified(patientId: String, alertId: String): Boolean

    fun markNotified(patientId: String, alertId: String)
}
