package ru.kardiomonitor.features.alerts.api

import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.alerts.domain.PatientAlertSyncStatus

/** Опубликованная точка управления синхронизацией оповещений для композиционного корня и UI. */
interface PatientAlertSync {
    val state: StateFlow<PatientAlertSyncStatus>

    fun start()

    fun refreshNow()
}
