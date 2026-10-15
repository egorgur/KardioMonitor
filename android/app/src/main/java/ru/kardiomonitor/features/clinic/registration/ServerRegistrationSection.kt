package ru.kardiomonitor.features.clinic.registration

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.platform.ui.SectionCard

/**
 * Точка входа слайса «Регистрация на сервере»: адрес сервера (для демо — ngrok) и имя
 * пациента → токен загрузки. После регистрации — кому и куда отправляются данные.
 */
@Composable
fun ServerRegistrationSection(
    repository: ServerRegistrationRepository,
    server: ClinicServer,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel { ServerRegistrationViewModel(repository, RegisterOnServer(server, repository)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ServerRegistrationContent(
        state = state,
        onAddressChange = viewModel::onAddressChange,
        onNameChange = viewModel::onNameChange,
        onRegister = viewModel::register,
        onForget = viewModel::forget,
        modifier = modifier,
    )
}

@Composable
private fun ServerRegistrationContent(
    state: ServerRegistrationUiState,
    onAddressChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onRegister: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard("Сервер клиники", modifier) {
        val registration = state.registration
        if (registration != null) {
            Text("Пациент: ${registration.patientName.value}")
            Text(state.serverHost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onForget) { Text("Отключиться от сервера") }
        } else {
            RegistrationForm(state, onAddressChange, onNameChange, onRegister)
        }
    }
}

@Composable
private fun RegistrationForm(
    state: ServerRegistrationUiState,
    onAddressChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onRegister: () -> Unit,
) {
    Text(
        "Данные браслета будут отправляться врачу. Адрес сервера выдаёт клиника.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.addressInput,
        onValueChange = onAddressChange,
        label = { Text("Адрес сервера") },
        placeholder = { Text("https://….ngrok-free.app") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.nameInput,
        onValueChange = onNameChange,
        label = { Text("Имя пациента") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    state.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Button(onClick = onRegister, enabled = state.canRegister) {
        Text(if (state.isRegistering) "Регистрация…" else "Зарегистрироваться")
    }
}
