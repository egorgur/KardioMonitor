package ru.kardiomonitor.staff.features.inventory.labeling

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.AssignmentReturn
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.kardiomonitor.staff.features.inventory.api.BandListEntry
import ru.kardiomonitor.staff.platform.ui.SecureWindowEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandDetailScreen(
    band: BandListEntry,
    isIssued: Boolean,
    onBack: () -> Unit,
    onSaveLabel: (String) -> Unit,
    onInspect: () -> Unit,
    onIssue: () -> Unit,
    onReturn: () -> Unit,
) {
    SecureWindowEffect()
    var label by remember(band.address.value) { mutableStateOf(band.label.orEmpty()) }
    var confirmReturn by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(band.label ?: "Браслет") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IdentityPanel(band, isIssued)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Метка на браслете") },
                supportingText = { Text("1–20 знаков, уникально в реестре") },
                singleLine = true,
            )
            OutlinedButton(onClick = { onSaveLabel(label) }, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить метку")
            }
            if (band.protocol == ru.kardiomonitor.staff.shared.BandProtocol.XiaomiBand9Spp) {
                Text("Band 9: только чтение. История, live-пульс и шаги за сегодня заблокированы. Выдача QR не подтверждает готовность мониторинга.")
            }
            if (!band.supported) {
                Text("Проверка и QR заблокированы: модель или протокол не поддерживается. Запись сохранена. Обратитесь к администратору; не меняйте протокол наугад и не удаляйте данные.")
            }
            Button(onClick = onInspect, enabled = band.supported, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.FactCheck, contentDescription = null)
                Text("  Проверить браслет")
            }
            FilledTonalButton(onClick = onIssue, enabled = band.supported, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.QrCode2, contentDescription = null)
                Text(if (isIssued) "  Показать QR снова" else "  Выдать и показать QR")
            }
            if (isIssued) {
                OutlinedButton(onClick = { confirmReturn = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.AssignmentReturn, contentDescription = null)
                    Text("  Отметить возврат")
                }
            }
        }
    }
    if (confirmReturn) {
        AlertDialog(
            onDismissRequest = { confirmReturn = false },
            title = { Text("Браслет вернулся в клинику?") },
            text = { Text("После возврата попросите прежнего пациента удалить KardioMonitor с телефона.") },
            confirmButton = {
                Button(onClick = { confirmReturn = false; onReturn() }) { Text("Отметить возврат") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmReturn = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun IdentityPanel(band: BandListEntry, issued: Boolean) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(band.address.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (issued) "НА РУКАХ" else "В КЛИНИКЕ", style = MaterialTheme.typography.labelLarge)
            }
            Text(band.model, style = MaterialTheme.typography.bodyMedium)
            Text(band.protocol.displayName, style = MaterialTheme.typography.bodySmall)
            Text(
                band.firmware?.let { "Прошивка $it" } ?: "Прошивка будет прочитана при проверке",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}
