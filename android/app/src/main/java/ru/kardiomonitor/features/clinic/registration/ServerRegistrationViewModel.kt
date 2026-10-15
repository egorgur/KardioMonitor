package ru.kardiomonitor.features.clinic.registration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository

/** Состояние секции «Сервер клиники». */
internal data class ServerRegistrationUiState(
    val registration: ServerRegistration? = null,
    val addressInput: String = "",
    val nameInput: String = "",
    val isRegistering: Boolean = false,
    val problem: String? = null,
) {
    val canRegister: Boolean get() = !isRegistering && addressInput.isNotBlank() && nameInput.isNotBlank()

    /** Адрес без схемы — короче на экране телефона. */
    val serverHost: String get() = registration?.address?.url?.substringAfter("://").orEmpty()
}

internal class ServerRegistrationViewModel(
    private val repository: ServerRegistrationRepository,
    private val registerOnServer: RegisterOnServer,
) : ViewModel() {
    private val form = MutableStateFlow(ServerRegistrationUiState())

    val state: StateFlow<ServerRegistrationUiState> =
        combine(repository.registration, form) { registration, current -> current.copy(registration = registration) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ServerRegistrationUiState())

    fun onAddressChange(value: String) = form.update { it.copy(addressInput = value, problem = null) }

    fun onNameChange(value: String) = form.update { it.copy(nameInput = value, problem = null) }

    fun register() {
        val current = form.value
        form.update { it.copy(isRegistering = true, problem = null) }
        viewModelScope.launch {
            val result = registerOnServer(current.addressInput, current.nameInput)
            form.update {
                it.copy(isRegistering = false, problem = (result as? RegistrationResult.Failed)?.reason)
            }
        }
    }

    fun forget() = repository.forget()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
