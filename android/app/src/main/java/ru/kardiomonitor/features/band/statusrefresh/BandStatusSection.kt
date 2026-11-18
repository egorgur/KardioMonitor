package ru.kardiomonitor.features.band.statusrefresh

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import ru.kardiomonitor.platform.ui.SectionCard

/** Отдельное управление read-only фоном без обещания клинической синхронизации. */
@Composable
fun BandStatusSection(settings: SyncSettings, modifier: Modifier = Modifier) {
    val enabled by settings.isBackgroundSyncEnabled.collectAsStateWithLifecycle()
    SectionCard("Фоновая проверка Band 9", modifier) {
        Text(
            "Только подключение, заряд и чтение настроек. Пульс и шаги не считываются. " +
                "Нужны разрешение Bluetooth и системная ассоциация браслета.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Проверять в фоне каждые 30 мин", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = settings::setBackgroundSyncEnabled)
        }
        Text("Android может отложить проверку для экономии заряда.", style = MaterialTheme.typography.bodySmall)
    }
}
