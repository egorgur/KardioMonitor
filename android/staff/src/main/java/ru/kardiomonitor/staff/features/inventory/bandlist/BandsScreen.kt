package ru.kardiomonitor.staff.features.inventory.bandlist

import ru.kardiomonitor.staff.features.inventory.api.BandListEntry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandsScreen(
    bands: List<BandListEntry>,
    query: String,
    issuedAddresses: Set<String>,
    onQueryChange: (String) -> Unit,
    onBandClick: (BandListEntry) -> Unit,
    onAdd: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Браслеты") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Добавить") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = onAdd,
                elevation = FloatingActionButtonDefaults.elevation(2.dp),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(query, onQueryChange)
            HandoffSummary(total = bands.size)
            if (bands.isEmpty()) EmptyInventory(hasQuery = query.isNotBlank(), onAdd = onAdd)
            else BandRows(bands, issuedAddresses, onBandClick)
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Метка или MAC") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
    )
}

@Composable
private fun HandoffSummary(total: Int) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Станция выдачи", style = MaterialTheme.typography.labelLarge)
            Text("$total в реестре", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BandRows(
    bands: List<BandListEntry>,
    issuedAddresses: Set<String>,
    onBandClick: (BandListEntry) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(bands, key = { it.address.value }) { band ->
            Row(
                Modifier.fillMaxWidth().clickable { onBandClick(band) }.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        band.label ?: "Без метки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(band.address.value, style = MaterialTheme.typography.bodyMedium)
                    Text(band.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                val isIssued = band.address.value in issuedAddresses
                Text(
                    when {
                        isIssued -> "На руках"
                        band.supported -> "В клинике"
                        else -> "Не поддерживается"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        isIssued -> MaterialTheme.colorScheme.tertiary
                        band.supported -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Открыть")
            }
            HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun EmptyInventory(hasQuery: Boolean, onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (hasQuery) "Ничего не найдено" else "Добавьте первый браслет", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (hasQuery) "Проверьте метку или MAC-адрес" else "Вставьте MAC и ключ — они сохранятся зашифрованными",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
