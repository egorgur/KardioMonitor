package ru.kardiomonitor.features.alerts.domain

/** Показывает системное уведомление; false — у приложения сейчас нет разрешения. */
interface PatientAlertNotifier {
    fun show(alert: PatientAlert): Boolean
}
