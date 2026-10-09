package ru.kardiomonitor.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ru.kardiomonitor.data.BandConfig

@Composable
fun SetupScreen(onSave: (BandConfig) -> Unit, modifier: Modifier = Modifier) {
    var mac by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val config = BandConfig.parse(text)
        if (config != null) onSave(config) else scanError = "В QR-коде нет MAC-адреса и ключа"
    }
    val manual = BandConfig.parse("$mac $key")

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
        Button(
            onClick = {
                scanError = null
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Наведите камеру на QR-код браслета")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сканировать QR-код") }
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Или вручную", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = mac, onValueChange = { mac = it.trim() },
            label = { Text("MAC-адрес") }, placeholder = { Text("E7:19:F3:6C:04:9E") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it.trim() },
            label = { Text("Ключ (32 hex-символа)") }, placeholder = { Text("0x0123…") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { manual?.let(onSave) },
            enabled = manual != null,
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
