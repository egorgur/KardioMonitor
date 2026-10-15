package ru.kardiomonitor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Switch
import ru.kardiomonitor.Connection
import ru.kardiomonitor.data.LastSync
import java.time.Instant
import java.time.ZoneId
import ru.kardiomonitor.UiState
import ru.kardiomonitor.data.BandSettings
import ru.kardiomonitor.features.monitoring.domain.DailySummary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class DashboardActions(
    val connect: () -> Unit,
    val disconnect: () -> Unit,
    val sync: () -> Unit,
    val toggleHeartRate: () -> Unit,
    val setHeartRateInterval: (Int) -> Unit,
    val setBackgroundSync: (Boolean) -> Unit,
    val showDay: (LocalDate) -> Unit,
    val forgetBand: () -> Unit,
    val dismissError: () -> Unit,
)

@Composable
fun DashboardScreen(state: UiState, actions: DashboardActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.band.error?.let { ErrorCard(it, actions.dismissError) }
        DeviceCard(state, actions)
        if (state.band.connection == Connection.CONNECTED) LiveHeartRateCard(state, actions.toggleHeartRate)
        HeartRateIntervalCard(state, actions.setHeartRateInterval)
        DayHeader(state.day, actions.showDay)
        DailySummaryCard(state.daySummary)
        SectionCard("Пульс, уд/мин") { HeartRateChart(state.daySamples, state.day) }
        SectionCard("Шаги по часам") { StepsChart(state.daySamples, state.day) }
        TextButton(onClick = actions.forgetBand) { Text("Сменить браслет") }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⚠ $message",
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    }
}

@Composable
private fun DeviceCard(state: UiState, actions: DashboardActions) {
    val status = when (state.band.connection) {
        Connection.DISCONNECTED -> "Не подключён"
        Connection.SEARCHING -> "Поиск браслета…"
        Connection.CONNECTING -> "Подключение…"
        Connection.AUTHENTICATING -> "Авторизация…"
        Connection.CONNECTED -> "Подключён"
    }
    SectionCard("Mi Smart Band 6") {
        Text("${state.config?.mac} · $status", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.band.connection == Connection.CONNECTED) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatTile("Батарея", state.band.battery?.let { "$it %" } ?: "—")
                StatTile("Шагов сегодня", state.band.stepsToday?.toString() ?: "—")
            }
        }
        if (state.band.background && state.band.connection != Connection.DISCONNECTED) {
            Text("Идёт фоновая синхронизация", style = MaterialTheme.typography.bodySmall)
        }
        state.band.syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Последняя синхронизация: " + (state.band.lastSync?.let(::formatLastSync) ?: "не было"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Синхронизировать в фоне каждые 30 мин", modifier = Modifier.weight(1f))
            Switch(checked = state.backgroundSync, onCheckedChange = actions.setBackgroundSync)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state.band.connection) {
                Connection.DISCONNECTED -> Button(onClick = actions.connect) { Text("Подключить") }
                Connection.CONNECTED -> {
                    Button(onClick = actions.sync, enabled = !state.band.syncing) { Text("Синхронизировать") }
                    OutlinedButton(onClick = actions.disconnect) { Text("Отключить") }
                }
                else -> {
                    CircularProgressIndicator(Modifier.width(24.dp))
                    OutlinedButton(onClick = actions.disconnect) { Text("Отмена") }
                }
            }
        }
    }
}

private fun formatLastSync(sync: LastSync): String {
    val time = Instant.ofEpochSecond(sync.at).atZone(ZoneId.systemDefault())
    val day = when (time.toLocalDate()) {
        LocalDate.now() -> ""
        LocalDate.now().minusDays(1) -> "вчера "
        else -> time.format(DateTimeFormatter.ofPattern("d MMM ", Locale.forLanguageTag("ru")))
    }
    val source = if (sync.background) "в фоне" else "вручную"
    return "$day${time.format(DateTimeFormatter.ofPattern("HH:mm"))} ($source) — ${sync.summary}"
}

@Composable
private fun LiveHeartRateCard(state: UiState, onToggle: () -> Unit) {
    SectionCard("Пульс сейчас") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                when {
                    !state.band.liveHeartRateOn -> "—"
                    state.band.liveHeartRate == null -> "…"
                    else -> state.band.liveHeartRate.toString()
                },
                fontSize = 56.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text("уд/мин", modifier = Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.band.liveHeartRateOn && state.band.liveHeartRate == null) {
            Text("Измерение… (5–15 с, браслет должен быть на руке)", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onToggle) { Text(if (state.band.liveHeartRateOn) "Остановить" else "Измерять") }
    }
}

@Composable
private fun HeartRateIntervalCard(state: UiState, onSelect: (Int) -> Unit) {
    SectionCard("Частота измерения пульса") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (minutes in BandSettings.HR_INTERVAL_OPTIONS) {
                FilterChip(
                    selected = state.band.hrInterval == minutes,
                    onClick = { onSelect(minutes) },
                    label = { Text(if (minutes == 0) "Выкл" else "$minutes мин") },
                )
            }
        }
        val hint = when (state.band.hrInterval) {
            null -> "Сейчас действует настройка из Mi Fitness."
            1 -> "Подробная картина пульса за сутки. Браслет разряжается быстрее всего."
            5 -> "Баланс между подробностью данных и зарядом браслета."
            0 -> "Пульс не будет записываться в историю. Измерение «Пульс сейчас» продолжит работать."
            else -> "Экономит заряд, но короткие подъёмы пульса можно пропустить."
        }
        Text(hint, style = MaterialTheme.typography.bodySmall)
        val status = when {
            state.band.hrInterval == null -> null
            !state.band.hrIntervalPending -> "✓ Записано в браслет"
            state.band.connection == Connection.CONNECTED -> "Записывается в браслет…"
            else -> "Будет записано в браслет при подключении"
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Mi Fitness при подключении к браслету может вернуть свою настройку — " +
                "приложение повторно записывает выбранную частоту при каждом подключении.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayHeader(day: LocalDate, onShowDay: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val title = when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onShowDay(day.minusDays(1)) }) { Text("‹ Назад") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { onShowDay(day.plusDays(1)) }, enabled = day < today) { Text("Вперёд ›") }
    }
}

@Composable
private fun DailySummaryCard(summary: DailySummary) {
    SectionCard("Итоги дня") {
        if (!summary.hasData) {
            Text(
                "Нет данных за этот день. Подключите браслет и синхронизируйте.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val min = summary.minHeartRate
        val max = summary.maxHeartRate
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile("Шаги", summary.steps.value.toString())
            StatTile("Пульс ср.", summary.averageHeartRate?.bpm?.toString() ?: "—")
            StatTile("Мин–макс", if (min == null || max == null) "—" else "${min.bpm}–${max.bpm}")
        }
        val recorded = summary.recordedMinutes
        Text(
            "Записано ${recorded / 60} ч ${recorded % 60} мин, пульс есть в ${summary.heartRateCoveragePercent}% минут" +
                if (summary.notWornMinutes > 0) ", браслет снят ${summary.notWornMinutes} мин" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}
