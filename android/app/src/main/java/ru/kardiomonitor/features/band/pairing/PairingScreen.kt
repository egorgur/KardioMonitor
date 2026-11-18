package ru.kardiomonitor.features.band.pairing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.clinic.domain.ClinicEnrollment
import ru.kardiomonitor.platform.ui.SecureWindowEffect

/**
 * Точка входа слайса «Привязка браслета»: QR-код клиники или ввод адреса и ключа вручную.
 * Отвязка — [ChangeBandButton].
 */
@Composable
fun PairingScreen(
    repository: BandRepository,
    clinicEnrollment: ClinicEnrollment,
    modifier: Modifier = Modifier,
) {
    SecureWindowEffect()
    val viewModel = viewModel { PairingViewModel(repository, clinicEnrollment) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::onScanned)
    }
    PairingContent(
        state = state,
        onScan = {
            viewModel.onScanStarted()
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Наведите камеру на QR-код браслета")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        },
        onProtocolChange = viewModel::onProtocolChange,
        onMacChange = viewModel::onMacChange,
        onKeyChange = viewModel::onKeyChange,
        onSave = viewModel::saveManual,
        modifier = modifier,
    )
}

@Composable
private fun PairingContent(
    state: PairingUiState,
    onScan: () -> Unit,
    onProtocolChange: (BandProtocol) -> Unit,
    onMacChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Подключение браслета", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Отсканируйте QR-код, выданный в клинике, или введите MAC-адрес и ключ браслета вручную.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onScan, enabled = !state.isPairing, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.isPairing) "Привязка…" else "Сканировать QR-код")
        }
        state.scanProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Или вручную", style = MaterialTheme.typography.titleMedium)
        for (protocol in BandProtocol.entries) {
            FilterChip(
                selected = state.protocol == protocol,
                enabled = !state.isPairing,
                onClick = { onProtocolChange(protocol) },
                label = { Text(protocol.label) },
            )
        }
        if (!state.protocol.canMonitorActivity) {
            Text("Band 9: только подключение, заряд и чтение настроек. Клинический мониторинг пока недоступен.")
        }
        OutlinedTextField(
            value = state.mac, onValueChange = onMacChange,
            label = { Text("MAC-адрес") }, placeholder = { Text("E7:19:F3:6C:04:9E") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.key, onValueChange = onKeyChange,
            label = { Text("Ключ (32 hex-символа)") }, placeholder = { Text("0x0123…") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = onSave,
            enabled = state.manualCredentials != null && !state.isPairing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить") }

        Text(
            "Браслет держит одно соединение: перед подключением закройте Mi Fitness " +
                "или отключите ей доступ к Bluetooth (разрешение «Устройства поблизости»).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
