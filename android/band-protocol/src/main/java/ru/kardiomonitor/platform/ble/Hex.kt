package ru.kardiomonitor.platform.ble

/** Байты в hex для журнала и тестовых векторов: `0a1b`. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
