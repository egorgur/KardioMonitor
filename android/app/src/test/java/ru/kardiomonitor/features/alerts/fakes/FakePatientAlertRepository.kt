package ru.kardiomonitor.features.alerts.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.alerts.domain.PatientAlert
import ru.kardiomonitor.features.alerts.domain.PatientAlertRepository

class FakePatientAlertRepository : PatientAlertRepository {
    private val current = MutableStateFlow<List<PatientAlert>>(emptyList())
    private val notifiedIds = mutableSetOf<String>()
    var selectedPatientId: String? = null
        private set
    var savedCursor: Long = 0
        private set

    override val alerts: StateFlow<List<PatientAlert>> = current

    override fun selectPatient(patientId: String?) {
        selectedPatientId = patientId
        if (patientId == null) current.value = emptyList()
    }

    override fun cursor(patientId: String): Long {
        require(patientId == selectedPatientId)
        return savedCursor
    }

    override fun save(patientId: String, page: List<PatientAlert>, cursor: Long): List<PatientAlert> {
        require(patientId == selectedPatientId)
        val known = current.value.mapTo(mutableSetOf(), PatientAlert::id)
        val incoming = page.mapTo(mutableSetOf(), PatientAlert::id)
        current.value = (current.value.filterNot { it.id in incoming } + page).sortedByDescending { it.createdAt }
        savedCursor = maxOf(savedCursor, cursor)
        return page.filterNot { it.id in known }
    }

    override fun wasNotified(patientId: String, alertId: String): Boolean {
        require(patientId == selectedPatientId)
        return alertId in notifiedIds
    }

    override fun markNotified(patientId: String, alertId: String) {
        require(patientId == selectedPatientId)
        notifiedIds += alertId
    }
}
