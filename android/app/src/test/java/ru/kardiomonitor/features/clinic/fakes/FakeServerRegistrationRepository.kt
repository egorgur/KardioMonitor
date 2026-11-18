package ru.kardiomonitor.features.clinic.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import java.io.IOException

class FakeServerRegistrationRepository(initial: ServerRegistration? = null) : ServerRegistrationRepository {
    private val current = MutableStateFlow(initial)
    var failure: String? = null

    override val registration: StateFlow<ServerRegistration?> = current

    override fun save(registration: ServerRegistration) {
        failure?.let { throw IOException(it) }
        current.value = registration
    }

    override fun forget() {
        current.value = null
    }
}
