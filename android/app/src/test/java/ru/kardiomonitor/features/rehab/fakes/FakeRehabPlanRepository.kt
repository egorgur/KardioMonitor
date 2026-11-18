package ru.kardiomonitor.features.rehab.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.rehab.domain.RehabPlan
import ru.kardiomonitor.features.rehab.domain.RehabPlanRepository

class FakeRehabPlanRepository(initial: RehabPlan? = null) : RehabPlanRepository {
    private val current = MutableStateFlow(initial)
    var selectedPatientId: String? = null
        private set

    override val plan: StateFlow<RehabPlan?> = current

    override fun selectPatient(patientId: String?) {
        selectedPatientId = patientId
        if (patientId == null) current.value = null
    }

    override fun save(patientId: String, plan: RehabPlan) {
        require(patientId == selectedPatientId)
        current.value = plan
    }
}
