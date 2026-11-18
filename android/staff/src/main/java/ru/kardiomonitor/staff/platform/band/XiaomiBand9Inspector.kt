package ru.kardiomonitor.staff.platform.band

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ru.kardiomonitor.bandprotocol.xiaomi.XiaomiReadOnlyClient
import ru.kardiomonitor.staff.features.bandcheck.domain.BandInspector
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckProblem
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionResult
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionStage
import ru.kardiomonitor.staff.shared.BandProtocol
import java.io.IOException

class XiaomiBand9Inspector(private val context: Context) : BandInspector {
    override suspend fun inspect(
        address: String,
        key: ByteArray,
        heartRateIntervalMinutes: Int,
        onStage: (InspectionStage) -> Unit,
        protocol: BandProtocol,
    ): InspectionResult {
        if (protocol != BandProtocol.XiaomiBand9Spp) return InspectionResult.Refused(CheckProblem.UnsupportedProtocol)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return InspectionResult.Refused(CheckProblem.NoPermission)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return InspectionResult.Refused(CheckProblem.NoBluetooth)
        val client = XiaomiReadOnlyClient(context)
        return try {
            if (!adapter.isEnabled) return InspectionResult.Refused(CheckProblem.BluetoothOff)
            onStage(InspectionStage.Connecting)
            onStage(InspectionStage.Authenticating)
            client.open(address, key)
            onStage(InspectionStage.Reading)
            val battery = client.readBattery()
            val settings = client.readHeartRateSettings()
            // open() already verifies HMAC and the exact M2345B1 / 2.3.98 model/firmware guard.
            InspectionResult.Inspected("2.3.98", battery, settings.intervalMinutes, settings.disabled)
        } catch (_: SecurityException) {
            InspectionResult.Refused(CheckProblem.NoPermission)
        } catch (_: IOException) {
            InspectionResult.Refused(CheckProblem.LinkFailed)
        } finally {
            client.close()
        }
    }
}
