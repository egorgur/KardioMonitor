package ru.kardiomonitor.staff.features.inventory.manualentry

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.FilterChip
import ru.kardiomonitor.staff.shared.BandProtocol
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.kardiomonitor.staff.app.BandDiscoveryUiState
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.platform.ui.SecureWindowEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(
    discovery: BandDiscoveryUiState,
    protocol: BandProtocol?,
    onSelectProtocol: (BandProtocol) -> Unit,
    onChoose: (BandAddress) -> Unit,
    onBack: () -> Unit,
    onFind: () -> Unit,
    onCancelFind: () -> Unit,
    onSave: (BandAddress, String, BandProtocol) -> Unit,
) {
    SecureWindowEffect()
    val context = LocalContext.current
    val permissions = bluetoothPermissions(protocol)
    var keyText by remember { mutableStateOf("") }
    var permissionDenied by remember { mutableStateOf(false) }
    val found = discovery as? BandDiscoveryUiState.Found
    val keyValid = remember(keyText, found?.address, protocol) { keyMatches(keyText, found, protocol) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) onFind() else permissionDenied = true
    }
    val requestSearch = {
        permissionDenied = false
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            onFind()
        } else {
            launcher.launch(permissions.toTypedArray())
        }
    }
    DisposableEffect(Unit) { onDispose(onCancelFind) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Добавить браслет") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ProtocolChoice(protocol) { keyText = ""; permissionDenied = false; onSelectProtocol(it) }
            if (protocol != null) DiscoveryStatus(discovery, permissionDenied, protocol)
            if (discovery !is BandDiscoveryUiState.Searching) {
                OutlinedButton(onClick = requestSearch, enabled = protocol != null, modifier = Modifier.fillMaxWidth()) {
                    Text(if (protocol == BandProtocol.XiaomiBand9Spp) "Сопряжённые устройства" else "Найти Mi Smart Band 6")
                }
            }
            if (protocol == BandProtocol.XiaomiBand9Spp && discovery is BandDiscoveryUiState.Choices) {
                discovery.devices.forEach { device ->
                    OutlinedButton(onClick = { onChoose(device.address) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${device.name ?: "Без имени"}\n${device.address.value}")
                    }
                }
            }
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ключ браслета") },
                placeholder = { Text("32 hex-символа") },
                supportingText = { Text(if (keyValid) "Ключ соответствует выбранному адресу и протоколу"
                    else "Введите hex-ключ или QR без полей регистрации, соответствующий выбранному браслету") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = { if (found != null && protocol != null) { onSave(found.address, keyText, protocol); keyText = "" } },
                enabled = found != null && keyValid && protocol != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Добавить браслет")
            }
        }
    }
}

private fun keyMatches(text: String, found: BandDiscoveryUiState.Found?, protocol: BandProtocol?): Boolean =
    found != null && protocol != null && BandKeyInput.parse(text, found.address, protocol) != null

@Composable
private fun ProtocolChoice(protocol: BandProtocol?, onSelect: (BandProtocol) -> Unit) {
    Text("Выберите модель и протокол", style = MaterialTheme.typography.titleMedium)
    listOf(BandProtocol.MiBand6Ble, BandProtocol.XiaomiBand9Spp).forEach { choice ->
        FilterChip(
            selected = protocol == choice,
            onClick = { onSelect(choice) },
            label = { Text(choice.displayName) },
        )
    }
    Text(
        when (protocol) {
            BandProtocol.MiBand6Ble -> "Положите нужный Mi Smart Band 6 рядом и закройте Mi Fitness. MAC-адрес определится по BLE."
            BandProtocol.XiaomiBand9Spp -> "Выберите уже сопряжённый Band 9 по MAC-адресу. Имя не подтверждает модель. Автоматического сопряжения нет. Только M2345B1 / 2.3.98; история, live-пульс и шаги за сегодня заблокированы."
            else -> "Протокол обязателен: он определяет способ подключения и QR-код."
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun DiscoveryStatus(state: BandDiscoveryUiState, permissionDenied: Boolean, protocol: BandProtocol) {
    val (title, detail) = when {
        permissionDenied -> "Нет доступа к Bluetooth" to "Разрешите устройства поблизости и повторите поиск."
        state is BandDiscoveryUiState.Idle -> "Устройство не выбрано" to
            if (protocol == BandProtocol.XiaomiBand9Spp) "Откройте список сопряжённых устройств." else "Оставьте рядом только нужный браслет."
        state is BandDiscoveryUiState.Searching -> if (protocol == BandProtocol.XiaomiBand9Spp)
            "Читаем список сопряжённых устройств" to "Bluetooth-поиск и сопряжение не запускаются."
            else "Ищем ближайший Mi Smart Band 6" to "Поиск займёт около 6 секунд."
        state is BandDiscoveryUiState.Choices -> if (state.devices.isEmpty())
            "Нет сопряжённых Classic-устройств" to "Сначала выполните сопряжение вне Kardio Staff и обновите список."
            else "Выберите устройство" to "Сверьте MAC-адрес нужного Band 9. Модель и ключ проверяются после добавления."
        state is BandDiscoveryUiState.Found -> "Браслет найден" to state.address.value
        state is BandDiscoveryUiState.Problem -> "Браслет не найден" to state.message
        else -> error("Неизвестное состояние поиска")
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state is BandDiscoveryUiState.Searching) CircularProgressIndicator()
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun bluetoothPermissions(protocol: BandProtocol?): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    if (protocol == BandProtocol.XiaomiBand9Spp) listOf(Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    if (protocol == BandProtocol.XiaomiBand9Spp) emptyList() else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
