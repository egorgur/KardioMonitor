package ru.kardiomonitor.staff.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    Scaffold(topBar = { TopAppBar(title = { Text("О программе") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Kardio Staff", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Станция подготовки и выдачи Mi Smart Band 6. Band 9: ограниченная проверка только чтением, мониторинг не подтверждён.")
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Данные остаются на устройстве", fontWeight = FontWeight.SemiBold)
                    Text("Ключи браслетов зашифрованы Android Keystore. Данные пациентов не сохраняются.")
                }
            }
            Text("Версия 0.1", style = MaterialTheme.typography.labelLarge)
            Text(
                "QR-коды создаются библиотекой ZXing. Протокол Huami реализован в KardioMonitor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
