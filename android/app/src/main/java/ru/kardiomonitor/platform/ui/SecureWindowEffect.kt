package ru.kardiomonitor.platform.ui

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

@Composable
fun SecureWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.let(SecureWindows::acquire)
        onDispose { window?.let(SecureWindows::release) }
    }
}

private object SecureWindows {
    private val leases = WeakHashMap<Window, Int>()

    @Synchronized
    fun acquire(window: Window) {
        if (leases.getOrDefault(window, 0) == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        leases[window] = leases.getOrDefault(window, 0) + 1
    }

    @Synchronized
    fun release(window: Window) {
        val remaining = leases.getOrDefault(window, 0) - 1
        if (remaining > 0) {
            leases[window] = remaining
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            leases.remove(window)
        }
    }
}
