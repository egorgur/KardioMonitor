package ru.kardiomonitor.staff.features.issuance.issuancelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.kardiomonitor.staff.features.issuance.domain.Issuance
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuanceHistoryScreen(history: List<Issuance>) {
    Scaffold(topBar = { TopAppBar(title = { Text("Журнал выдачи") }) }) { padding ->
        if (history.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Выдач пока нет", style = MaterialTheme.typography.titleLarge)
                Text(
                    "После показа QR здесь появятся дата, метка и статус возврата.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(history, key = { it.id }) { issuance ->
                    IssuanceRow(issuance)
                    HorizontalDivider(Modifier.padding(start = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun IssuanceRow(issuance: Issuance) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(issuance.label ?: "Без метки", fontWeight = FontWeight.SemiBold)
            Text(issuance.address, style = MaterialTheme.typography.bodySmall)
            Text(issuance.protocol.displayName, style = MaterialTheme.typography.bodySmall)
            Text("Выдан ${format(issuance.issuedAt)}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            issuance.returnedAt?.let { "Возвращён\n${format(it)}" } ?: "На руках",
            style = MaterialTheme.typography.labelMedium,
            color = if (issuance.isOpen) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
    }
}

private fun format(instant: java.time.Instant): String = FORMATTER.format(instant.atZone(ZoneId.systemDefault()))

private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
