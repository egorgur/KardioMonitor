package ru.kardiomonitor.features.alerts.list

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kardiomonitor.features.alerts.api.PatientAlertSync
import ru.kardiomonitor.features.alerts.api.PatientAlertText
import ru.kardiomonitor.features.alerts.domain.PatientAlert
import ru.kardiomonitor.features.alerts.domain.PatientAlertRepository
import ru.kardiomonitor.platform.ui.SectionCard
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class PatientAlertListUiState(
    val alerts: List<PatientAlert>,
    val isSyncing: Boolean,
    val problem: String?,
    val canNotify: Boolean,
)

/** Список оповещений клиники, кнопка обновления и запрос разрешения системных уведомлений. */
@Composable
fun PatientAlertSection(
    repository: PatientAlertRepository,
    synchronizer: PatientAlertSync,
    modifier: Modifier = Modifier,
) {
    val alerts by repository.alerts.collectAsStateWithLifecycle()
    val state by synchronizer.state.collectAsStateWithLifecycle()
    if (!state.isRegistered) return
    val context = LocalContext.current
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) synchronizer.refreshNow()
    }
    PatientAlertContent(
        state = PatientAlertListUiState(alerts, state.isSyncing, state.problem, hasNotificationPermission(context)),
        onRequestPermission = { permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onRefresh = synchronizer::refreshNow,
        modifier = modifier,
    )
}

@Composable
private fun PatientAlertContent(
    state: PatientAlertListUiState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier,
) {
    SectionCard("Оповещения клиники", modifier) {
        Text(
            "Оповещения приходят с задержкой синхронизации до 30–60 минут и не заменяют экстренную помощь.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.canNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(onClick = onRequestPermission) { Text("Разрешить уведомления") }
        }
        state.problem?.let { Text("Ошибка обновления: $it", color = MaterialTheme.colorScheme.error) }
        if (state.alerts.isEmpty()) Text("Оповещений пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.alerts.forEach { PatientAlertRow(it) }
        }
        OutlinedButton(onClick = onRefresh, enabled = !state.isSyncing) {
            Text(if (state.isSyncing) "Обновление…" else "Обновить оповещения")
        }
    }
}

@Composable
private fun PatientAlertRow(alert: PatientAlert) {
    val time = alert.createdAt.atZone(ZoneId.systemDefault()).format(ALERT_TIME)
    Column {
        Text(PatientAlertText.title(alert), style = MaterialTheme.typography.titleSmall)
        Text(PatientAlertText.message(alert))
        Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private val ALERT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
