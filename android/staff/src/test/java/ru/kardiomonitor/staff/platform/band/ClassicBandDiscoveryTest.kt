package ru.kardiomonitor.staff.platform.band

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicBandDiscoveryTest {
    @Test
    fun `Band9 выбор допускает Classic и Dual но не BLE или неизвестный транспорт`() {
        assertTrue(isClassicCandidate(BluetoothDevice.DEVICE_TYPE_CLASSIC))
        assertTrue(isClassicCandidate(BluetoothDevice.DEVICE_TYPE_DUAL))
        assertFalse(isClassicCandidate(BluetoothDevice.DEVICE_TYPE_LE))
        assertFalse(isClassicCandidate(BluetoothDevice.DEVICE_TYPE_UNKNOWN))
    }
}
