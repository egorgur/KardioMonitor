package ru.kardiomonitor.features.band.pairing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.domain.BandRepository

/**
 * Точка входа слайса «Привязка браслета»: QR-код клиники или ввод адреса и ключа вручную.
 * Отвязка — [ChangeBandButton].
 */
@Composable
fun PairingScreen(repository: BandRepository, connection: BandConnection, modifier: Modifier = Modifier) {
    val viewModel = viewModel { PairingViewModel(repository, connection) }
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
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Сканировать QR-код") }
        state.scanProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Или вручную", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.mac, onValueChange = onMacChange,
            label = { Text("MAC-адрес") }, placeholder = { Text("E7:19:F3:6C:04:9E") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.key, onValueChange = onKeyChange,
            label = { Text("Ключ (32 hex-символа)") }, placeholder = { Text("0x0123…") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = onSave,
            enabled = state.manualCredentials != null,
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
