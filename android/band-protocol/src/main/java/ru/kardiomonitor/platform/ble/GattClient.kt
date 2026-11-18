package ru.kardiomonitor.platform.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Последовательная coroutine-обёртка над Android BluetoothGatt. */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context) {
    private class OpResult(val status: Int, val value: ByteArray? = null)
    private enum class OpKind { Services, CharacteristicWrite, DescriptorWrite, CharacteristicRead }
    private class PendingOp(
        val gatt: BluetoothGatt,
        val kind: OpKind,
        val characteristic: UUID?,
        val result: CompletableDeferred<OpResult>,
    )

    private val opMutex = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: PendingOp? = null
    @Volatile private var connecting: CompletableDeferred<Unit>? = null
    private val listeners = ConcurrentHashMap<UUID, (ByteArray) -> Unit>()

    @Volatile var onDisconnected: (() -> Unit)? = null
    val isConnected get() = gatt != null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state $newState, status $status")
            if (gatt !== g) {
                g.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connecting?.complete(Unit)
                return
            }
            val error = IOException("соединение потеряно (status $status)")
            connecting?.completeExceptionally(error)
            pending?.result?.completeExceptionally(error)
            g.close()
            if (gatt === g) {
                gatt = null
                onDisconnected?.invoke()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            completePending(g, OpKind.Services, null, OpResult(status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            completePending(g, OpKind.CharacteristicWrite, c.uuid, OpResult(status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            completePending(g, OpKind.DescriptorWrite, d.characteristic.uuid, OpResult(status))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            completePending(g, OpKind.CharacteristicRead, c.uuid, OpResult(status, value))
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                completePending(g, OpKind.CharacteristicRead, c.uuid, OpResult(status, c.value?.clone()))
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt !== g) return
            listeners[c.uuid]?.invoke(value)
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (gatt === g && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                c.value?.clone()?.let { listeners[c.uuid]?.invoke(it) }
            }
        }
    }

    private fun completePending(g: BluetoothGatt, kind: OpKind, characteristic: UUID?, value: OpResult) {
        val operation = pending ?: return
        if (operation.gatt !== g || operation.kind != kind || operation.characteristic != characteristic) return
        operation.result.complete(value)
    }

    suspend fun findDevice(adapter: BluetoothAdapter, mac: String, timeoutMs: Long = 15_000): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: throw IOException("Bluetooth выключен")
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        scanner.stopScan(this)
                        if (cont.isActive) cont.resume(result.device)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner.startScan(listOf(filter), settings, callback)
                cont.invokeOnCancellation { scanner.stopScan(callback) }
            }
        }
    }

    suspend fun connect(device: BluetoothDevice) {
        val deferred = CompletableDeferred<Unit>()
        connecting = deferred
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withResponseTimeout(CONNECT_TIMEOUT_MS, "подключение") { deferred.await() }
        } catch (e: Exception) {
            disconnect()
            throw e
        } finally {
            connecting = null
        }
        val status = op(OpKind.Services, null) { it.discoverServices() }.status
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("не удалось получить сервисы ($status)")
    }

    fun disconnect() {
        val current = gatt ?: return
        gatt = null
        listeners.clear()
        pending?.result?.completeExceptionally(IOException("соединение закрыто"))
        current.disconnect()
        current.close()
    }

    fun hasCharacteristic(uuid: UUID) = findCharacteristic(uuid) != null

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    private fun requireCharacteristic(uuid: UUID) =
        findCharacteristic(uuid) ?: throw IOException("нет характеристики $uuid")

    private suspend fun op(
        kind: OpKind,
        characteristic: UUID?,
        timeoutMs: Long = 10_000,
        start: (BluetoothGatt) -> Boolean,
    ): OpResult =
        opMutex.withLock {
            val current = gatt ?: throw IOException("нет соединения с браслетом")
            val deferred = CompletableDeferred<OpResult>()
            pending = PendingOp(current, kind, characteristic, deferred)
            try {
                if (!start(current)) throw IOException("Android не принял GATT-операцию")
                withResponseTimeout(timeoutMs, "GATT-операция") { deferred.await() }
            } catch (failure: IOException) {
                disconnect()
                throw failure
            } catch (cancelled: CancellationException) {
                disconnect()
                throw cancelled
            } finally {
                if (pending?.result === deferred) pending = null
            }
        }

    suspend fun read(uuid: UUID): ByteArray {
        val characteristic = requireCharacteristic(uuid)
        val result = op(OpKind.CharacteristicRead, uuid) { it.readCharacteristic(characteristic) }
        Log.i(TAG, "read ${shortUuid(uuid)} -> status ${result.status} ${result.value?.let(::describe)}")
        if (result.status != BluetoothGatt.GATT_SUCCESS) throw IOException("ошибка чтения $uuid: ${result.status}")
        return result.value ?: ByteArray(0)
    }

    suspend fun write(uuid: UUID, data: ByteArray) {
        val characteristic = requireCharacteristic(uuid)
        val properties = characteristic.properties
        val type = if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val result = op(OpKind.CharacteristicWrite, uuid) { current ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.writeCharacteristic(characteristic, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = type
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                current.writeCharacteristic(characteristic)
            }
        }
        Log.i(TAG, "write ${shortUuid(uuid)} ${describe(data)} -> status ${result.status}")
        if (result.status != BluetoothGatt.GATT_SUCCESS) throw IOException("ошибка записи $uuid: ${result.status}")
    }

    suspend fun enableNotifications(uuid: UUID, listener: (ByteArray) -> Unit) {
        val characteristic = requireCharacteristic(uuid)
        listeners[uuid] = listener
        val descriptor = characteristic.getDescriptor(CCCD) ?: throw IOException("у $uuid нет CCCD")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val result = op(OpKind.DescriptorWrite, uuid) { current ->
            current.setCharacteristicNotification(characteristic, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                current.writeDescriptor(descriptor)
            }
        }
        if (result.status != BluetoothGatt.GATT_SUCCESS) throw IOException("не включились уведомления $uuid")
    }

    companion object {
        const val TAG = "KardioBLE"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private fun shortUuid(uuid: UUID) = uuid.toString().substring(4, 8)
        private fun describe(data: ByteArray) = if (data.size <= 12) data.toHex() else "[${data.size} B]"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
