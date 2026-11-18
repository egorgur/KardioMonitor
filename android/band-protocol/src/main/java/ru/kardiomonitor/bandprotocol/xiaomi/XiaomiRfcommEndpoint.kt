package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException

/** Узкая граница для проверки безопасной подготовки RFCOMM без Android в тестах. */
internal interface XiaomiRfcommEndpoint {
    val enabled: Boolean
    val bonded: Boolean
    val discovering: Boolean
    fun cancelDiscovery(): Boolean
    fun createSecureTransport(): XiaomiTransport
}

internal fun prepareXiaomiRfcomm(endpoint: XiaomiRfcommEndpoint): XiaomiTransport {
    if (!endpoint.enabled) throw IOException("Bluetooth недоступен")
    if (!endpoint.bonded) throw IOException("Требуется существующее сопряжение")
    // cancelDiscovery запрашивает остановку; false не доказывает наличие активного поиска.
    // Android BluetoothAdapter.cancelDiscovery и проверенный локальный ProbeActivity.java.
    endpoint.cancelDiscovery()
    if (endpoint.discovering) throw IOException("Поиск Bluetooth не остановлен")
    return endpoint.createSecureTransport()
}
