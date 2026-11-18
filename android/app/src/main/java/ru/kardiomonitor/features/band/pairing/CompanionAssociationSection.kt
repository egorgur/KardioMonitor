package ru.kardiomonitor.features.band.pairing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.platform.ui.SectionCard

/** Одноразовая системная подготовка уже привязанного браслета к запросам клиники (ADR-0013). */
@Composable
fun CompanionAssociationSection(
    association: CompanionBandAssociation,
    repository: BandRepository,
    modifier: Modifier = Modifier,
) {
    val band by repository.band.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        refresh += 1
    }
    val current = band ?: return
    val isAssociated = remember(current.credentials.address, refresh) {
        association.isAssociated(current.credentials.address)
    }
    if (isAssociated) return
    val canMonitor = current.credentials.protocol.canMonitorActivity
    SectionCard(if (canMonitor) "Запрос пульса от клиники" else "Фоновая связь с Band 9", modifier) {
        Text(
            (if (canMonitor) "Разрешите Android запускать измерение после синхронизации. "
            else "Разрешите Android проверять заряд и читать настройки в фоне. Мониторинг недоступен. ") +
                "Разбудите браслет, выберите его в системном окне и подтвердите.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                problem = null
                association.request(
                    current.credentials.address,
                    onChooser = { launcher.launch(IntentSenderRequest.Builder(it).build()) },
                    onFailure = { problem = "Не удалось разрешить фоновую связь: $it" },
                )
            },
        ) { Text(if (canMonitor) "Разрешить запросы клиники" else "Разрешить фоновую проверку") }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
