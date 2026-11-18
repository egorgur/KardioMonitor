package ru.kardiomonitor.staff.features.bandcheck.check

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import ru.kardiomonitor.staff.shared.BandProtocol
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.kardiomonitor.staff.app.BandCheckUiState
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckOutcome
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckProblem
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandCheckScreen(
    label: String,
    state: BandCheckUiState,
    onBack: () -> Unit,
    onInspect: () -> Unit,
    protocol: BandProtocol,
) {
    var permissionDenied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permissions = bluetoothPermissions(protocol)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) onInspect() else permissionDenied = true
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Проверка · $label") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                if (protocol == BandProtocol.XiaomiBand9Spp)
                    "Закройте Mi Fitness. Будут прочитаны только модель, прошивка, заряд и настройки. Интервал не изменяется. История, live-пульс и шаги за сегодня заблокированы."
                else "Положите браслет рядом и закройте Mi Fitness. Проверка займёт до минуты.",
                style = MaterialTheme.typography.bodyLarge,
            )
            ReadinessStrip(
                state.currentStage,
                state.result != null && state.result.outcome !is CheckOutcome.Failed,
                protocol,
            )
            CheckStatus(state, protocol)
            if (permissionDenied) {
                Text(
                    "Разрешите доступ к устройствам поблизости в настройках приложения.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    permissionDenied = false
                    if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                        onInspect()
                    } else {
                        launcher.launch(permissions.toTypedArray())
                    }
                },
                enabled = !state.running && protocol != BandProtocol.Unknown,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.result == null) "Начать проверку" else "Проверить ещё раз")
            }
        }
    }
}

@Composable
private fun ReadinessStrip(current: InspectionStage?, complete: Boolean, protocol: BandProtocol) {
    val stages = if (protocol == BandProtocol.XiaomiBand9Spp)
        listOf(InspectionStage.Connecting, InspectionStage.Authenticating, InspectionStage.Reading)
        else InspectionStage.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (protocol == BandProtocol.XiaomiBand9Spp) "Проверка чтения Band 9" else "Готовность к выдаче",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            stages.forEach { stage ->
                val reached = complete || current != null && stage.ordinal <= current.ordinal
                Box(
                    Modifier.weight(1f).height(8.dp).background(
                        if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.shapes.extraSmall,
                    ),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (protocol == BandProtocol.XiaomiBand9Spp) "СВЯЗЬ" else "ПОИСК", style = MaterialTheme.typography.labelSmall)
            Text("КЛЮЧ", style = MaterialTheme.typography.labelSmall)
            Text("ДАННЫЕ", style = MaterialTheme.typography.labelSmall)
            if (protocol != BandProtocol.XiaomiBand9Spp) Text("ИНТЕРВАЛ", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CheckStatus(state: BandCheckUiState, protocol: BandProtocol) {
    val result = state.result
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.running -> {
                    Text(stageText(state.currentStage), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Не уносите браслет и не открывайте Mi Fitness")
                }
                result == null -> {
                    Text("Ожидает проверки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (protocol == BandProtocol.XiaomiBand9Spp)
                        "Будут проверены ключ и модель; заряд и интервал только читаются. Готовность мониторинга не подтверждается."
                        else "Будут проверены ключ, прошивка, заряд и интервал пульса")
                }
                else -> {
                    ResultContent(result.outcome, result.firmware?.value, result.battery?.percent, protocol)
                    if (protocol == BandProtocol.XiaomiBand9Spp && result.outcome !is CheckOutcome.Failed) {
                        Text("Заряд: ${result.battery?.percent?.let { "$it %" } ?: "не получен"}")
                        Text("Интервал: " + when (val interval = result.heartRateIntervalMinutes) {
                            null -> "не получен"
                            0 -> "Smart (автоматически)"
                            else -> "$interval мин"
                        })
                        Text("Автопульс: " + when (result.heartRateDisabled) {
                            true -> "выключен"
                            false -> "включён"
                            null -> "неизвестно"
                        })
                        Text("Настройки не изменялись. Мониторинг Band 9 не подтверждён.")
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultContent(outcome: CheckOutcome, firmware: String?, battery: Int?, protocol: BandProtocol) {
    val (title, detail) = when (outcome) {
        CheckOutcome.Ready -> "Готов к выдаче" to "Ключ подходит, настройки записаны"
        is CheckOutcome.Warning -> (if (protocol == BandProtocol.XiaomiBand9Spp)
            "Чтение завершено с ограничениями" else "Можно выдать с предупреждением") to outcome.reasons.joinToString(" · ")
        is CheckOutcome.Failed -> "Проверка не пройдена" to problemText(outcome.problem)
    }
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(detail)
    if (firmware != null || battery != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            firmware?.let { Text("Прошивка $it", style = MaterialTheme.typography.labelLarge) }
            battery?.let { Text("Заряд $it %", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

private fun stageText(stage: InspectionStage?): String = when (stage) {
    InspectionStage.Searching -> "Ищем браслет"
    InspectionStage.Connecting -> "Устанавливаем связь"
    InspectionStage.Authenticating -> "Проверяем ключ"
    InspectionStage.Reading -> "Читаем прошивку и заряд"
    InspectionStage.Configuring -> "Записываем интервал 1 минута"
    null -> "Подготовка"
}

private fun problemText(problem: CheckProblem): String = when (problem) {
    CheckProblem.NotFound -> "Браслет не найден. Поднесите его ближе и закройте Mi Fitness."
    CheckProblem.AuthRejected -> "Ключ не подошёл. Браслет мог быть перепривязан."
    CheckProblem.LinkFailed -> "Связь прервалась. Повторите проверку рядом с браслетом."
    CheckProblem.NoBluetooth -> "На телефоне нет Bluetooth."
    CheckProblem.BluetoothOff -> "Включите Bluetooth и повторите."
    CheckProblem.NoPermission -> "Разрешите доступ к устройствам поблизости."
    CheckProblem.UnsupportedProtocol -> "Неизвестный протокол. Подключение заблокировано."
    CheckProblem.UnsupportedModel -> "Модель не соответствует выбранному протоколу."
    CheckProblem.KeyUnavailable -> "Ключ недоступен. Разблокируйте телефон и повторите. Не удаляйте данные приложения; при повторной ошибке обратитесь к администратору."
}

private fun bluetoothPermissions(protocol: BandProtocol): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    if (protocol == BandProtocol.XiaomiBand9Spp) listOf(Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    if (protocol == BandProtocol.XiaomiBand9Spp) emptyList() else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
