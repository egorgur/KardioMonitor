package ru.kardiomonitor.staff.features.inventory.manualentry

import kotlin.io.encoding.Base64
import ru.kardiomonitor.staff.features.inventory.domain.AuthKey
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.shared.BandProtocol

/** Структурированный QR никогда не разбирается как свободный текст с hex-ключом. */
internal object BandKeyInput {
    fun parse(text: String, address: BandAddress, protocol: BandProtocol): AuthKey? {
        val value = text.trim()
        if (protocol == BandProtocol.Unknown) return null
        if (value.contains("kardio", ignoreCase = true)) return structured(value, address, protocol)
        val addresses = Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}").findAll(value).toList()
        if (addresses.size > 1 || addresses.singleOrNull()?.value?.uppercase()?.let { it != address.value } == true) return null
        val keys = Regex("(?i)(?<![0-9a-f])(?:0x)?[0-9a-f]{32}(?![0-9a-f])").findAll(value).toList()
        if (keys.size != 1) return null
        return AuthKey.of(keys.single().value)
    }

    private fun structured(value: String, address: BandAddress, protocol: BandProtocol): AuthKey? {
        val fields = value.split(';')
        return when {
            fields.size == 3 && fields[0] == "kardio" && protocol == BandProtocol.MiBand6Ble -> {
                if (BandAddress.findIn(fields[1])?.value != fields[1].uppercase() ||
                    !fields[1].equals(address.value, ignoreCase = true)
                ) return null
                if (!Regex("[0-9a-fA-F]{32}").matches(fields[2])) return null
                AuthKey.of(fields[2])
            }
            fields.size == 4 && fields[0] == "kardio3" -> {
                if (fields[1] != protocol.id || fields[2] != address.value.replace(":", "")) return null
                decodeKey(fields[3])
            }
            else -> null
        }
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun decodeKey(encoded: String): AuthKey? {
        if (!Regex("[A-Za-z0-9_-]{22}").matches(encoded)) return null
        val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val bytes = try {
            codec.decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            if (bytes.size != 16 || codec.encode(bytes) != encoded) null else AuthKey.fromBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }
}
