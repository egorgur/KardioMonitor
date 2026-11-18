package ru.kardiomonitor.staff.features.issuance.domain

/** Секретная строка QR v3. Полное значение доступно только внутри [useEncoded]. */
class PairingCode private constructor(private val encoded: String) {
    fun <T> useEncoded(block: (String) -> T): T = block(encoded)

    override fun toString(): String = "PairingCode(<hidden>)"

    companion object {
        private val ADDRESS = Regex("""[0-9A-F]{2}(?::[0-9A-F]{2}){5}""")
        private val KEY = Regex("""[0-9a-f]{32}""")

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        fun of(
            address: String,
            keyHex: String,
            protocol: ru.kardiomonitor.staff.shared.BandProtocol =
                ru.kardiomonitor.staff.shared.BandProtocol.MiBand6Ble,
        ): PairingCode {
            require(ADDRESS.matches(address)) { "Некорректный MAC-адрес" }
            require(KEY.matches(keyHex)) { "Некорректный ключ" }
            require(protocol != ru.kardiomonitor.staff.shared.BandProtocol.Unknown) {
                "Неизвестный протокол"
            }
            val bytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val encodedKey = try {
                kotlin.io.encoding.Base64.UrlSafe
                    .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).encode(bytes)
            } finally {
                bytes.fill(0)
            }
            return PairingCode("kardio3;${protocol.id};${address.replace(":", "")};$encodedKey")
        }
    }
}
