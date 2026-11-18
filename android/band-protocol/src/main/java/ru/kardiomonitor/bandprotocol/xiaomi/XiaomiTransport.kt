package ru.kardiomonitor.bandprotocol.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal interface XiaomiTransport : Closeable {
    val input: InputStream
    val output: OutputStream
    fun connect()
}

/** Только secure RFCOMM к существующему bond; никогда не инициирует pairing/discovery. */
internal class XiaomiRfcommTransport(private val socket: BluetoothSocket) : XiaomiTransport {
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override fun connect() { socket.connect() }
    override fun close() { socket.close() }
    override fun toString(): String = "XiaomiRfcommTransport"

    companion object {
        @SuppressLint("MissingPermission")
        fun create(context: Context, address: String): XiaomiTransport {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("Bluetooth недоступен")
            val device = adapter.getRemoteDevice(address)
            return prepareXiaomiRfcomm(object : XiaomiRfcommEndpoint {
                override val enabled: Boolean get() = adapter.isEnabled
                override val bonded: Boolean get() = device.bondState == BluetoothDevice.BOND_BONDED
                override val discovering: Boolean get() = adapter.isDiscovering
                override fun cancelDiscovery(): Boolean = adapter.cancelDiscovery()
                override fun createSecureTransport(): XiaomiTransport = XiaomiRfcommTransport(
                    device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")),
                )
            })
        }
    }
}
