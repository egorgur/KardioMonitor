package ru.kardiomonitor.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ru.kardiomonitor.features.band.api.BandAssociation
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.monitoring.domain.SyncSettings

/** Перед каждым фоновым SPP-сеансом перепроверяет разрешения, настройку и текущую CDM-ассоциацию. */
internal class BackgroundBandAccess(
    private val context: Context,
    private val repository: BandRepository,
    private val association: BandAssociation,
    private val settings: SyncSettings,
) {
    fun isAllowed(): Boolean {
        val band = repository.band.value ?: return false
        if (!settings.isBackgroundSyncEnabled.value || !association.isAssociated(band.credentials.address)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
