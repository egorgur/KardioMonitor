package ru.kardiomonitor.features.alerts.domain

/** Состояние получения оповещений клиники. */
data class PatientAlertSyncStatus(
    val isRegistered: Boolean = false,
    val isSyncing: Boolean = false,
    val problem: String? = null,
)
