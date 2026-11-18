package ru.kardiomonitor.staff.platform.ui

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

@Composable
fun SecureWindowEffect(maximumBrightness: Boolean = false) {
    val view = LocalView.current
    DisposableEffect(view, maximumBrightness) {
        val window = (view.context as? Activity)?.window
        window?.let { SecureWindowRegistry.acquire(it, maximumBrightness) }
        onDispose {
            window?.let { SecureWindowRegistry.release(it, maximumBrightness) }
        }
    }
}

/** Удерживает FLAG_SECURE при одновременной композиции исходящего и входящего экранов. */
private object SecureWindowRegistry {
    private data class Lease(var secureCount: Int, var brightnessCount: Int, val originalBrightness: Float)

    private val leases = WeakHashMap<Window, Lease>()

    @Synchronized
    fun acquire(window: Window, maximumBrightness: Boolean) {
        val lease = leases.getOrPut(window) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Lease(0, 0, window.attributes.screenBrightness)
        }
        lease.secureCount++
        if (maximumBrightness) {
            lease.brightnessCount++
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
    }

    @Synchronized
    fun release(window: Window, maximumBrightness: Boolean) {
        val lease = leases[window] ?: return
        lease.secureCount--
        if (maximumBrightness) lease.brightnessCount--
        if (lease.brightnessCount == 0) {
            window.attributes = window.attributes.apply { screenBrightness = lease.originalBrightness }
        }
        if (lease.secureCount == 0) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            leases.remove(window)
        }
    }
}
