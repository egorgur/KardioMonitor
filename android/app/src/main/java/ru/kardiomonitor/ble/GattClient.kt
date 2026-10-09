package ru.kardiomonitor.ble

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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Обёртка над BluetoothGatt на корутинах. Android допускает только одну GATT-операцию
 * одновременно, поэтому операции выполняются строго по очереди (mutex) и ждут свой callback.
 * Уведомления раздаются слушателям синхронно в потоке Binder — порядок сохраняется.
 */
@SuppressLint("MissingPermission") // разрешения запрашивает MainActivity до подключения
class GattClient(private val context: Context) {
    private class OpResult(val status: Int, val value: ByteArray? = null)

    private val opMutex = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableDeferred<OpResult>? = null
    @Volatile private var connecting: CompletableDeferred<Unit>? = null
    private val listeners = ConcurrentHashMap<UUID, (ByteArray) -> Unit>()

    /** Вызывается при потере соединения (не при disconnect()). */
    @Volatile var onDisconnected: (() -> Unit)? = null

    val isConnected get() = gatt != null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state $newState, status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connecting?.complete(Unit)
                return
            }
            val error = IOException("соединение потеряно (status $status)")
            connecting?.completeExceptionally(error)
            pending?.completeExceptionally(error)
            g.close()
            if (gatt === g) {
                gatt = null
                onDisconnected?.invoke()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(OpResult(status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(OpResult(status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(OpResult(status))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            pending?.complete(OpResult(status, value))
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                pending?.complete(OpResult(status, c.value?.clone()))
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            listeners[c.uuid]?.invoke(value)
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                c.value?.clone()?.let { listeners[c.uuid]?.invoke(it) }
            }
        }
    }

    /** Ищет браслет сканированием: так Android узнаёт тип адреса (у Mi Band он random static). */
    suspend fun findDevice(adapter: BluetoothAdapter, mac: String, timeoutMs: Long = 15_000): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: throw IOException("Bluetooth выключен")
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val scanCallback = object : ScanCallback() {
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
                scanner.startScan(listOf(filter), settings, scanCallback)
                cont.invokeOnCancellation { scanner.stopScan(scanCallback) }
            }
        }
    }

    suspend fun connect(device: BluetoothDevice) {
        val deferred = CompletableDeferred<Unit>()
        connecting = deferred
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(20_000) { deferred.await() }
        } catch (e: Exception) {
            disconnect()
            throw e
        } finally {
            connecting = null
        }
        val status = op { it.discoverServices() }.status
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("не удалось получить сервисы ($status)")
    }

    fun disconnect() {
        val g = gatt ?: return
        gatt = null
        listeners.clear()
        pending?.completeExceptionally(IOException("соединение закрыто"))
        g.disconnect()
        g.close()
    }

    fun hasCharacteristic(uuid: UUID) = findCharacteristic(uuid) != null

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    private fun requireCharacteristic(uuid: UUID) =
        findCharacteristic(uuid) ?: throw IOException("нет характеристики $uuid")

    private suspend fun op(timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): OpResult =
        opMutex.withLock {
            val g = gatt ?: throw IOException("нет соединения с браслетом")
            val deferred = CompletableDeferred<OpResult>()
            pending = deferred
            try {
                if (!start(g)) throw IOException("Android не принял GATT-операцию")
                // Таймаут — сбой связи, а не отмена: иначе catch (CancellationException) у
                // вызывающего молча завершит корутину вместо показа ошибки.
                withTimeoutOrNull(timeoutMs) { deferred.await() }
                    ?: throw IOException("браслет не ответил за $timeoutMs мс")
            } finally {
                pending = null
            }
        }

    suspend fun read(uuid: UUID): ByteArray {
        val c = requireCharacteristic(uuid)
        val result = op { it.readCharacteristic(c) }
        Log.i(TAG, "read ${shortUuid(uuid)} -> status ${result.status} ${result.value?.let(::describe)}")
        if (result.status != BluetoothGatt.GATT_SUCCESS) throw IOException("ошибка чтения $uuid: ${result.status}")
        return result.value ?: ByteArray(0)
    }

    /** Тип записи выбирается по свойствам характеристики. */
    suspend fun write(uuid: UUID, data: ByteArray) {
        val c = requireCharacteristic(uuid)
        val props = c.properties
        val type = if (props and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
            props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val result = op { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
        Log.i(TAG, "write ${shortUuid(uuid)} ${describe(data)} -> status ${result.status}")
        if (result.status != BluetoothGatt.GATT_SUCCESS) throw IOException("ошибка записи $uuid: ${result.status}")
    }

    suspend fun enableNotifications(uuid: UUID, listener: (ByteArray) -> Unit) {
        val c = requireCharacteristic(uuid)
        listeners[uuid] = listener
        val descriptor = c.getDescriptor(CCCD) ?: throw IOException("у $uuid нет CCCD")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val result = op { g ->
            g.setCharacteristicNotification(c, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }
        if (result.status != BluetoothGatt.GATT_SUCCESS) throw IOException("не включились уведомления $uuid")
    }

    companion object {
        const val TAG = "KardioBLE"

        /** 00002a39-0000-1000-… -> 2a39 */
        private fun shortUuid(uuid: UUID) = uuid.toString().substring(4, 8)

        /** Короткие команды — целиком; длинные (авторизация, куски сообщений) — только длина. */
        private fun describe(data: ByteArray) = if (data.size <= 12) data.toHex() else "[${data.size} B]"

        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
