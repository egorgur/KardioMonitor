package ru.kardiomonitor.staff.features.issuance.qrdisplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.kardiomonitor.staff.features.issuance.domain.PairingCode
import ru.kardiomonitor.staff.platform.ui.QrImage
import ru.kardiomonitor.staff.platform.ui.SecureWindowEffect
import ru.kardiomonitor.staff.platform.ui.QrPalette
import kotlin.coroutines.resume

@Composable
fun PairingCodeScreen(
    code: PairingCode,
    onDisplayed: () -> Unit,
    onClose: () -> Unit,
    protocol: ru.kardiomonitor.staff.shared.BandProtocol,
) {
    SecureWindowEffect(maximumBrightness = true)
    BackHandler(onBack = onClose)
    val view = LocalView.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val close = androidx.compose.runtime.rememberUpdatedState(onClose)
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) close.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val startedElapsed = rememberSaveable { SystemClock.elapsedRealtime() }
    val expiresElapsed = rememberSaveable { startedElapsed + DISPLAY_MILLIS }
    val expiresWall = rememberSaveable { System.currentTimeMillis() + DISPLAY_MILLIS }
    var secondsLeft by remember { mutableIntStateOf(secondsRemaining(startedElapsed, expiresElapsed, expiresWall)) }
    val image = remember(code) { code.useEncoded { QrImage.create(it, QR_SIZE) } }
    LaunchedEffect(code) {
        awaitNextDraw(view)
        onDisplayed()
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft = secondsRemaining(startedElapsed, expiresElapsed, expiresWall)
        }
        onClose()
    }
    Box(
        Modifier.fillMaxSize().background(QrPalette.Background).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Сканируйте в обновлённом KardioMonitor", style = MaterialTheme.typography.headlineSmall, color = QrPalette.Foreground)
            Text(protocol.displayName, color = QrPalette.Foreground)
            if (protocol == ru.kardiomonitor.staff.shared.BandProtocol.XiaomiBand9Spp) {
                Text("Только чтение. Мониторинг Band 9 не подтверждён.", color = QrPalette.Foreground)
            }
            Image(
                image,
                contentDescription = "QR-код привязки браслета",
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f),
            )
            Text(
                "Экран закроется через ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}",
                color = QrPalette.SecondaryText,
            )
            Button(onClick = onClose) { Text("Готово") }
        }
    }
}

private suspend fun awaitNextDraw(view: View) = suspendCancellableCoroutine { continuation ->
    var observed = false
    val listener = object : ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
            if (observed) return
            observed = true
            view.post {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
    view.viewTreeObserver.addOnDrawListener(listener)
    continuation.invokeOnCancellation {
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
    }
}

private fun secondsRemaining(startedElapsed: Long, expiresElapsed: Long, expiresWall: Long): Int {
    val nowElapsed = SystemClock.elapsedRealtime()
    if (nowElapsed < startedElapsed) return 0
    val elapsedRemaining = expiresElapsed - nowElapsed
    val wallRemaining = expiresWall - System.currentTimeMillis()
    val remaining = minOf(elapsedRemaining, wallRemaining).coerceAtLeast(0)
    return ((remaining + 999) / 1_000).toInt()
}

private const val DISPLAY_SECONDS = 120
private const val DISPLAY_MILLIS = DISPLAY_SECONDS * 1_000L
private const val QR_SIZE = 768
