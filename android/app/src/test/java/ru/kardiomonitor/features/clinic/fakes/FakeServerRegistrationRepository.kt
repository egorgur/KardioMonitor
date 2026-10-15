package ru.kardiomonitor.features.clinic.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository

class FakeServerRegistrationRepository(initial: ServerRegistration? = null) : ServerRegistrationRepository {
    private val current = MutableStateFlow(initial)

    override val registration: StateFlow<ServerRegistration?> = current

    override fun save(registration: ServerRegistration) {
        current.value = registration
    }

    override fun forget() {
        current.value = null
    }
}
