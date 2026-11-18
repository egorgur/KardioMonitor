package ru.kardiomonitor.staff.platform.band

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandDiscoveryResult
import ru.kardiomonitor.staff.features.inventory.domain.BondedBandCandidate
import ru.kardiomonitor.staff.features.inventory.domain.BondedBandDiscovery

class ClassicBandDiscovery(private val context: Context) : BondedBandDiscovery {
    @SuppressLint("MissingPermission")
    override suspend fun alreadyBondedClassicDevices(): BandDiscoveryResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return BandDiscoveryResult.NoPermission
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return BandDiscoveryResult.NoBluetooth
            if (!adapter.isEnabled) return BandDiscoveryResult.BluetoothOff
            val devices = adapter.bondedDevices.orEmpty()
                .filter { it.bondState == BluetoothDevice.BOND_BONDED && isClassicCandidate(it.type) }
                .map { BondedBandCandidate(BandAddress.of(it.address), it.name) }
                .sortedBy { it.address.value }
            BandDiscoveryResult.Candidates(devices)
        } catch (_: SecurityException) {
            BandDiscoveryResult.NoPermission
        }
    }
}

internal fun isClassicCandidate(type: Int): Boolean =
    type == BluetoothDevice.DEVICE_TYPE_CLASSIC || type == BluetoothDevice.DEVICE_TYPE_DUAL
