package ru.kardiomonitor.features.band.domain

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** MAC без разделителей и Base64url-ключ не распознаются старым парсером Band 6. */
@OptIn(ExperimentalEncodingApi::class)
internal object PairingPayloadV3 {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    fun parse(text: String): BandCredentials? {
        val fields = text.split(';')
        if (fields.size < 4 || fields[0] != "kardio3") return null
        val protocol = BandProtocol.fromWireName(fields[1]) ?: return null
        if (!fields[2].matches(Regex("[0-9A-F]{12}"))) return null
        if (!fields[3].matches(Regex("[A-Za-z0-9_-]{22}"))) return null
        if (fields.drop(4).any { !it.startsWith("s=") && !it.startsWith("e=") }) return null
        return try {
            val bytes = base64.decode(fields[3])
            if (bytes.size != 16 || base64.encode(bytes) != fields[3]) {
                return null
            }
            val key = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            bytes.fill(0)
            BandCredentials(BandAddress(fields[2].chunked(2).joinToString(":")), AuthKey(key), protocol)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
