package ru.kardiomonitor.platform.huami

/** Числа протокола Huami — little-endian. */
internal fun le16(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

internal fun le32(data: ByteArray, offset: Int): Int =
    le16(data, offset) or (le16(data, offset + 2) shl 16)

internal fun le32(value: Int): ByteArray = ByteArray(4) { (value shr (8 * it)).toByte() }
