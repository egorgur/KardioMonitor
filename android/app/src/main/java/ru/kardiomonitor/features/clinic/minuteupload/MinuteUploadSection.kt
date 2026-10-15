package ru.kardiomonitor.features.clinic.minuteupload

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kardiomonitor.platform.ui.SectionCard
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Точка входа слайса на экране: итог отправки минут врачу и кнопка «Отправить сейчас». */
@Composable
fun MinuteUploadSection(uploader: MinuteUploader, modifier: Modifier = Modifier) {
    val state by uploader.state.collectAsStateWithLifecycle()
    if (state.isRegistered) MinuteUploadContent(state, onUploadNow = uploader::uploadNow, modifier = modifier)
}

@Composable
private fun MinuteUploadContent(state: MinuteUploadState, onUploadNow: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard("Отправка врачу", modifier) {
        Text(summaryOf(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.problem?.let { Text("Ошибка: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onUploadNow, enabled = !state.isUploading) {
            Text(if (state.isUploading) "Отправка…" else "Отправить сейчас")
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** История уходит на сервер после каждой синхронизации; «пульс сейчас» — во время измерения. */
private fun summaryOf(state: MinuteUploadState): String {
    val at = state.lastAttemptAt?.atZone(ZoneId.systemDefault())?.format(TIME)
    return when {
        state.isUploading -> "Отправляем новые минуты…"
        at == null -> "История отправляется после каждой синхронизации"
        state.lastSentMinutes == 0 -> "В $at новых минут не было — сервер получил всё"
        state.lastSentMinutes != null -> "В $at отправлено ${state.lastSentMinutes} мин"
        else -> "Последняя попытка в $at"
    }
}
