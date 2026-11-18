package ru.kardiomonitor.staff.platform.band

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandDiscovery
import ru.kardiomonitor.staff.features.inventory.domain.BandDiscoveryResult

class BleBandDiscovery(private val context: Context) : BandDiscovery {
    @SuppressLint("MissingPermission")
    override suspend fun nearestMiBand6(): BandDiscoveryResult {
        if (!hasPermissions()) return BandDiscoveryResult.NoPermission
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return BandDiscoveryResult.NoBluetooth
        if (!adapter.isEnabled) return BandDiscoveryResult.BluetoothOff
        val scanner = adapter.bluetoothLeScanner ?: return BandDiscoveryResult.BluetoothOff
        var strongest: ScanResult? = null
        var failed = false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val current = strongest
                if (isMiBand6(result) && (current == null || result.rssi > current.rssi)) {
                    strongest = result
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                failed = true
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        try {
            delay(SCAN_MILLIS)
        } finally {
            scanner.stopScan(callback)
        }
        if (failed) return BandDiscoveryResult.ScanFailed
        val result = strongest ?: return BandDiscoveryResult.NotFound
        return BandDiscoveryResult.Found(
            BandAddress.of(result.device.address),
            result.scanRecord?.deviceName,
        )
    }

    private fun isMiBand6(result: ScanResult): Boolean {
        val name = result.scanRecord?.deviceName?.trim() ?: return false
        return isMiBand6AdvertisementName(name)
    }

    private fun hasPermissions(): Boolean = permissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private companion object {
        const val SCAN_MILLIS = 6_000L
    }
}

internal fun isMiBand6AdvertisementName(name: String): Boolean =
    MI_BAND_6_NAME.matches(name)

private val MI_BAND_6_NAME =
    Regex("""(?i)^\s*(?:mi(?:\s+smart)?|xiaomi(?:\s+smart)?)\s+band\s+6(?:\s+nfc)?\s*$""")
