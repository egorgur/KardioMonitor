package ru.kardiomonitor.features.band.pairing

import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.clinic.domain.EnrollmentCode
import ru.kardiomonitor.features.clinic.domain.ServerAddress

/** Распознанный QR: данные браслета и, для v2, приглашение клиники. */
internal data class PairingCode(
    val credentials: BandCredentials,
    val invitation: ClinicInvitation?,
) {
    companion object {
        fun parse(text: String): PairingCode? {
            val credentials = BandCredentials.parse(text) ?: return null
            val fields = text.trim().split(';')
            val serverFields = fields.filter { it.startsWith("s=") }
            val enrollmentFields = fields.filter { it.startsWith("e=") }
            if (serverFields.isEmpty() && enrollmentFields.isEmpty()) return PairingCode(credentials, null)
            if (!hasInvitationPrefix(fields) || serverFields.size != 1 || enrollmentFields.size != 1) return null
            return try {
                val address = ServerAddress.parse(serverFields.single().removePrefix("s="))
                require(address.url.startsWith("https://")) { "QR-код должен содержать HTTPS" }
                val code = EnrollmentCode(enrollmentFields.single().removePrefix("e="))
                PairingCode(credentials, ClinicInvitation(address, code))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun hasInvitationPrefix(fields: List<String>): Boolean =
            (fields.size >= 5 && fields[0] == "kardio") || (fields.size >= 6 && fields[0] == "kardio3")
    }
}

/** Адрес сервера и одноразовый код из QR v2. */
internal data class ClinicInvitation(val address: ServerAddress, val code: EnrollmentCode)
