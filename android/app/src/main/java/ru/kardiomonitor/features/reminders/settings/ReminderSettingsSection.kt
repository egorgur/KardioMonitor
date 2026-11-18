package ru.kardiomonitor.features.reminders.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kardiomonitor.features.reminders.domain.IntervalReminderSettings
import ru.kardiomonitor.features.reminders.domain.MoveReminderSettings
import ru.kardiomonitor.features.reminders.domain.ReminderInterval
import ru.kardiomonitor.features.reminders.domain.ReminderRepository
import ru.kardiomonitor.features.reminders.domain.ReminderSettings
import ru.kardiomonitor.features.reminders.domain.ReminderWindow
import ru.kardiomonitor.platform.ui.SectionCard
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Настройки трёх локальных видов; данные остаются только на телефоне. */
@Composable
fun ReminderSettingsSection(repository: ReminderRepository, modifier: Modifier = Modifier) {
    val settings by repository.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val canNotify = hasNotificationPermission(context)
    val today = LocalDate.now()
    var isMutedToday by remember(today) { mutableStateOf(repository.facts(today).isMuted) }
    val save: (ReminderSettings) -> Unit = { updated ->
        val isFirstEnabled = !settings.hasEnabledReminders && updated.hasEnabledReminders
        repository.save(updated)
        if (isFirstEnabled && !canNotify) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    SectionCard("Напоминания", modifier) {
        Text(
            "Работают без интернета. Каждый вид можно выключить и в настройках Android.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.hasEnabledReminders && !canNotify) {
            OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Разрешить уведомления")
            }
        }
        MoveSettings(settings, save)
        DailyTargetSettings(settings, save)
        WearBandSettings(settings, save)
        OutlinedButton(
            onClick = {
                repository.mute(today)
                isMutedToday = true
            },
            enabled = !isMutedToday,
        ) {
            Text(if (isMutedToday) "Сегодня напоминания выключены" else "Не напоминать сегодня")
        }
    }
}

@Composable
private fun MoveSettings(settings: ReminderSettings, save: (ReminderSettings) -> Unit) {
    val move = settings.move
    ReminderToggle("Пора пройтись", move.schedule.enabled) { enabled ->
        save(settings.copy(move = move.copy(schedule = move.schedule.copy(enabled = enabled))))
    }
    if (!move.schedule.enabled) return
    IntervalChoice(move.schedule.interval) { interval ->
        save(settings.copy(move = move.copy(schedule = move.schedule.copy(interval = interval))))
    }
    NumberChoice("Порог", "${move.stepsPerHour} шагов/ч", onMinus = {
        save(settings.copy(move = move.copy(stepsPerHour = (move.stepsPerHour - 50).coerceAtLeast(50))))
    }, onPlus = {
        save(settings.copy(move = move.copy(stepsPerHour = (move.stepsPerHour + 50).coerceAtMost(10_000))))
    })
    IntervalSchedule(move.schedule) { schedule -> save(settings.copy(move = MoveReminderSettings(schedule, move.stepsPerHour))) }
}

@Composable
private fun DailyTargetSettings(settings: ReminderSettings, save: (ReminderSettings) -> Unit) {
    val daily = settings.dailyTarget
    ReminderToggle("Норма дня", daily.enabled) { save(settings.copy(dailyTarget = daily.copy(enabled = it))) }
    if (!daily.enabled) return
    TimeChoice("Время", daily.time, onMinus = {
        save(settings.copy(dailyTarget = daily.copy(time = daily.time.minusHours(1))))
    }, onPlus = {
        save(settings.copy(dailyTarget = daily.copy(time = daily.time.plusHours(1))))
    })
}

@Composable
private fun WearBandSettings(settings: ReminderSettings, save: (ReminderSettings) -> Unit) {
    val wear = settings.wearBand
    ReminderToggle("Наденьте браслет", wear.enabled) { save(settings.copy(wearBand = wear.copy(enabled = it))) }
    if (!wear.enabled) return
    IntervalChoice(wear.interval) { save(settings.copy(wearBand = wear.copy(interval = it))) }
    IntervalSchedule(wear) { save(settings.copy(wearBand = it)) }
}

@Composable
private fun ReminderToggle(title: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun IntervalChoice(selected: ReminderInterval, onSelected: (ReminderInterval) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ReminderInterval.entries.forEach { interval ->
            FilterChip(
                selected = interval == selected,
                onClick = { onSelected(interval) },
                label = { Text(intervalLabel(interval)) },
            )
        }
    }
}

@Composable
private fun IntervalSchedule(settings: IntervalReminderSettings, onChange: (IntervalReminderSettings) -> Unit) {
    TimeChoice("Начало", settings.window.start, onMinus = {
        val start = settings.window.start.minusHours(1)
        if (start.isBefore(settings.window.end.minusHours(1))) onChange(settings.copy(window = ReminderWindow(start, settings.window.end)))
    }, onPlus = {
        val start = settings.window.start.plusHours(1)
        if (!start.isAfter(settings.window.end.minusHours(1))) onChange(settings.copy(window = ReminderWindow(start, settings.window.end)))
    })
    TimeChoice("Конец", settings.window.end, onMinus = {
        val end = settings.window.end.minusHours(1)
        if (!end.isBefore(settings.window.start.plusHours(1))) onChange(settings.copy(window = ReminderWindow(settings.window.start, end)))
    }, onPlus = {
        val end = settings.window.end.plusHours(1)
        if (end != LocalTime.MIDNIGHT) onChange(settings.copy(window = ReminderWindow(settings.window.start, end)))
    })
    DayChoice(settings.days) { onChange(settings.copy(days = it)) }
}

@Composable
private fun TimeChoice(label: String, time: LocalTime, onMinus: () -> Unit, onPlus: () -> Unit) {
    NumberChoice(label, "%02d:%02d".format(time.hour, time.minute), onMinus, onPlus)
}

@Composable
private fun NumberChoice(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label: $value", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DayChoice(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day in selected,
                onClick = {
                    val changed = if (day in selected) selected - day else selected + day
                    if (changed.isNotEmpty()) onChange(changed)
                },
                label = { Text(dayLabel(day)) },
            )
        }
    }
}

private fun intervalLabel(interval: ReminderInterval): String = when (interval) {
    ReminderInterval.HalfHour -> "30м"
    ReminderInterval.OneHour -> "1ч"
    ReminderInterval.OneAndHalfHours -> "1,5ч"
    ReminderInterval.TwoHours -> "2ч"
    ReminderInterval.ThreeHours -> "3ч"
}

private fun dayLabel(day: DayOfWeek): String = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[day.ordinal]

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
