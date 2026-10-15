package ru.kardiomonitor.features.band.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.platform.ui.ProblemCard
import ru.kardiomonitor.platform.ui.SectionCard
import ru.kardiomonitor.platform.ui.StatTile

/**
 * Точка входа слайса «Подключение»: статус сеанса связи, заряд, кнопки «Подключить» /
 * «Отключить». Перед подключением запрашивает разрешения Bluetooth.
 *
 * @param connectedDetails показатели рядом с зарядом, пока браслет подключён (шаги сегодня).
 */
@Composable
fun ConnectionSection(
    connection: BandConnection,
    repository: BandRepository,
    modifier: Modifier = Modifier,
    connectedDetails: @Composable () -> Unit = {},
) {
    val viewModel = viewModel { ConnectionViewModel(connection, repository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted -> if (granted.values.all { it }) viewModel.connect()
    }
    val connect = {
        if (hasPermissions(context)) viewModel.connect() else permissionRequest.launch(BLUETOOTH_PERMISSIONS)
    }
    ConnectionContent(
        state = state,
        onConnect = connect,
        onDisconnect = viewModel::disconnect,
        onDismissProblem = viewModel::dismissProblem,
        modifier = modifier,
        connectedDetails = connectedDetails,
    )
}

@Composable
private fun ConnectionContent(
    state: ConnectionUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissProblem: () -> Unit,
    modifier: Modifier = Modifier,
    connectedDetails: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.problem?.let { ProblemCard(it, onDismissProblem) }
        SectionCard("Mi Smart Band 6") {
            Text(state.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.isConnected) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatTile("Батарея", state.battery)
                    connectedDetails()
                }
            }
            if (state.isBackgroundSession) {
                Text("Идёт фоновая синхронизация", style = MaterialTheme.typography.bodySmall)
            }
            ConnectionButtons(state, onConnect, onDisconnect)
        }
    }
}

@Composable
private fun ConnectionButtons(state: ConnectionUiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            state.isConnected -> OutlinedButton(onClick = onDisconnect) { Text("Отключить") }
            state.isInProgress -> {
                CircularProgressIndicator(Modifier.width(24.dp))
                OutlinedButton(onClick = onDisconnect) { Text("Отмена") }
            }
            else -> Button(onClick = onConnect) { Text("Подключить") }
        }
    }
}

private fun hasPermissions(context: Context): Boolean = BLUETOOTH_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private val BLUETOOTH_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // BLE-сканирование до Android 12
    }
