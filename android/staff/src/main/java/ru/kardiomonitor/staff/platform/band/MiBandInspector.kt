package ru.kardiomonitor.staff.platform.band

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.coroutineScope
import ru.kardiomonitor.platform.ble.GattClient
import ru.kardiomonitor.platform.huami.AuthException
import ru.kardiomonitor.platform.huami.MiBand6
import ru.kardiomonitor.staff.features.bandcheck.domain.BandInspector
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckProblem
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionResult
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionStage
import java.io.IOException
import ru.kardiomonitor.staff.shared.BandProtocol

class MiBandInspector(private val context: Context) : BandInspector {
    override suspend fun inspect(
        address: String,
        key: ByteArray,
        heartRateIntervalMinutes: Int,
        onStage: (InspectionStage) -> Unit,
        protocol: BandProtocol,
    ): InspectionResult = coroutineScope {
        if (protocol != BandProtocol.MiBand6Ble) {
            return@coroutineScope InspectionResult.Refused(CheckProblem.UnsupportedProtocol)
        }
        prerequisitesProblem()?.let { return@coroutineScope InspectionResult.Refused(it) }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@coroutineScope InspectionResult.Refused(CheckProblem.NoBluetooth)
        val gatt = GattClient(context)
        try {
            onStage(InspectionStage.Searching)
            val device = gatt.findDevice(adapter, address)
                ?: return@coroutineScope InspectionResult.Refused(CheckProblem.NotFound)
            onStage(InspectionStage.Connecting)
            gatt.connect(device)
            inspectConnected(MiBand6(gatt, this), key, heartRateIntervalMinutes, onStage)
        } catch (_: AuthException) {
            InspectionResult.Refused(CheckProblem.AuthRejected)
        } catch (_: IOException) {
            InspectionResult.Refused(CheckProblem.LinkFailed)
        } catch (_: SecurityException) {
            InspectionResult.Refused(CheckProblem.NoPermission)
        } catch (_: IllegalArgumentException) {
            InspectionResult.Refused(CheckProblem.LinkFailed)
        } catch (_: IndexOutOfBoundsException) {
            InspectionResult.Refused(CheckProblem.LinkFailed)
        } finally {
            gatt.disconnect()
        }
    }

    private suspend fun inspectConnected(
        band: MiBand6,
        key: ByteArray,
        intervalMinutes: Int,
        onStage: (InspectionStage) -> Unit,
    ): InspectionResult {
        onStage(InspectionStage.Authenticating)
        band.authenticate(key)
        Log.i(GattClient.TAG, "авторизация успешна")
        onStage(InspectionStage.Reading)
        val firmware = band.readFirmware()
        val battery = band.readBattery()
        onStage(InspectionStage.Configuring)
        band.setHeartRateInterval(intervalMinutes)
        return InspectionResult.Inspected(firmware, battery)
    }

    private fun prerequisitesProblem(): CheckProblem? {
        if (!hasPermissions()) return CheckProblem.NoPermission
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return CheckProblem.NoBluetooth
        return if (adapter.isEnabled) null else CheckProblem.BluetoothOff
    }

    private fun hasPermissions(): Boolean = permissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
