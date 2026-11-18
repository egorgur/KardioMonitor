package ru.kardiomonitor.platform.huami

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.kardiomonitor.platform.ble.GattClient
import ru.kardiomonitor.platform.ble.toHex
import ru.kardiomonitor.platform.ble.withResponseTimeout
import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/** Протокол Mi Band 6 поверх [GattClient], независимый от домена приложения. */
class MiBand6(private val gatt: GattClient, private val scope: CoroutineScope) {
    suspend fun authenticate(key: ByteArray) {
        if (gatt.hasCharacteristic(CHUNKED_WRITE) && gatt.hasCharacteristic(CHUNKED_READ)) authenticate2021(key)
        else authenticateLegacy(key)
    }

    private suspend fun authenticate2021(key: ByteArray) {
        val messages = Channel<ChunkedDecoder.Message>(Channel.UNLIMITED)
        val decoder = ChunkedDecoder()
        gatt.enableNotifications(CHUNKED_READ) { data ->
            val message = decoder.feed(data) ?: return@enableNotifications
            message.ack?.let { ack -> scope.launch { writeBestEffort(CHUNKED_READ, ack) } }
            if (!message.encrypted) messages.trySend(message)
        }
        val encoder = ChunkedEncoder()
        val auth = Auth2021(key)

        suspend fun send(data: ByteArray) {
            for (chunk in encoder.encode(ENDPOINT_AUTH, data)) gatt.write(CHUNKED_WRITE, chunk)
        }

        suspend fun expect(command: Int): ByteArray = withResponseTimeout(RESPONSE_TIMEOUT_MS, "авторизация") {
            var payload: ByteArray
            do {
                val message = messages.receive()
                payload = message.payload
                val matches = message.endpoint == ENDPOINT_AUTH && payload.size >= 3 &&
                    payload[0] == 0x10.toByte() && payload[1] == command.toByte()
            } while (!matches)
            if (payload[2] != 0x01.toByte()) throw AuthException(authError(payload))
            payload
        }

        send(auth.pubkeyCommand())
        val challenge = expect(0x04)
        send(auth.answerChallenge(challenge))
        expect(0x05)
    }

    private suspend fun authenticateLegacy(key: ByteArray) {
        val responses = Channel<ByteArray>(Channel.UNLIMITED)
        gatt.enableNotifications(AUTH) { responses.trySend(it) }

        suspend fun expect(prefix: ByteArray): ByteArray = withResponseTimeout(RESPONSE_TIMEOUT_MS, "авторизация") {
            val response = responses.receive()
            if (response.size < 3 || !response.copyOf(3).contentEquals(prefix)) throw AuthException(authError(response))
            response
        }

        gatt.write(AUTH, byteArrayOf(0x82.toByte(), 0x00, 0x02, 0x01, 0x00))
        val random = expect(byteArrayOf(0x10, 0x82.toByte(), 0x01)).copyOfRange(3, 19)
        gatt.write(AUTH, byteArrayOf(0x83.toByte(), 0x00) + aesEcb(random, key))
        expect(byteArrayOf(0x10, 0x83.toByte(), 0x01))
    }

    private fun authError(response: ByteArray): String {
        val code = response.copyOf(minOf(3, response.size)).toHex()
        return when (code) {
            "100525", "108304", "108308" -> "неверный ключ авторизации"
            "108307" -> "прошивка требует другой протокол авторизации"
            else -> "неожиданный ответ браслета: $code"
        }
    }

    suspend fun readFirmware(): String = decodeFirmware(gatt.read(FIRMWARE_REVISION))

    suspend fun readBattery(): Int = gatt.read(BATTERY)[1].toInt() and 0xFF
    suspend fun readSteps(): Int = parseSteps(gatt.read(STEPS))

    suspend fun subscribeSteps(onSteps: (Int) -> Unit) {
        gatt.enableNotifications(STEPS) { onSteps(parseSteps(it)) }
    }

    private fun parseSteps(data: ByteArray) = if (data.size >= 5) le32(data, 1) else 0

    suspend fun startHeartRate(onValue: (Int) -> Unit) {
        gatt.enableNotifications(HR_MEASUREMENT) { data ->
            val isUint16 = data.isNotEmpty() && data[0].toInt() and 0x01 != 0
            if (data.size < (if (isUint16) 3 else 2)) return@enableNotifications
            onValue(if (isUint16) le16(data, 1) else data[1].toInt() and 0xFF)
        }
        gatt.write(HR_CONTROL, byteArrayOf(0x15, 0x02, 0x00))
        gatt.write(HR_CONTROL, byteArrayOf(0x15, 0x01, 0x00))
        writeBestEffort(SENSOR, byteArrayOf(0x01, 0x03, 0x19))
        gatt.write(HR_CONTROL, byteArrayOf(0x15, 0x01, 0x01))
        writeBestEffort(SENSOR, byteArrayOf(0x02))
    }

    private suspend fun writeBestEffort(uuid: UUID, data: ByteArray) {
        try {
            gatt.write(uuid, data)
        } catch (e: IOException) {
            Log.w(GattClient.TAG, "необязательная запись в $uuid не удалась", e)
        }
    }

    suspend fun pingHeartRate() = gatt.write(HR_CONTROL, byteArrayOf(0x16))
    suspend fun stopHeartRate() = gatt.write(HR_CONTROL, byteArrayOf(0x15, 0x01, 0x00))
    suspend fun setHeartRateInterval(minutes: Int) = gatt.write(HR_CONTROL, heartRateIntervalCommand(minutes))

    /** Выгружает сырые поминутные записи, не удаляя историю с браслета. */
    suspend fun fetchActivity(
        since: ZonedDateTime,
        onProgress: (minutesReceived: Int) -> Unit,
    ): List<ActivityRecord> {
        val control = Channel<ByteArray>(Channel.UNLIMITED)
        val packets = HistoryPackets()
        gatt.enableNotifications(FETCH_CONTROL) { control.trySend(it) }
        gatt.enableNotifications(FETCH_DATA, packets::add)

        val samples = mutableListOf<ActivityRecord>()
        var from = since
        repeat(MAX_ROUNDS) {
            packets.reset()
            gatt.write(FETCH_CONTROL, byteArrayOf(0x01, 0x01) + encodeTime(from))
            val response = expectFetchResponse(control, command = 0x01)
            if (le32(response, 3) == 0) return samples
            val start = decodeTime(response, 7)

            gatt.write(FETCH_CONTROL, byteArrayOf(0x02))
            expectFetchResponse(control, command = 0x02)
            val chunk = parseActivityRecords(start.toInstant(), packets.records())
            onProgress(samples.size + chunk.size)
            if (chunk.isEmpty()) return samples
            samples += chunk
            from = start.plusMinutes(chunk.size.toLong())
            if (!from.isBefore(ZonedDateTime.now())) return samples
        }
        return samples
    }

    private suspend fun expectFetchResponse(control: Channel<ByteArray>, command: Int): ByteArray =
        withResponseTimeout(FETCH_TIMEOUT_MS, "выгрузка истории") {
            var response: ByteArray
            do {
                response = control.receive()
            } while (response.size < 3 || response[0] != 0x10.toByte() || response[1] != command.toByte())
            if (response[2] != 0x01.toByte()) {
                throw IOException("браслет отклонил команду $command: ${response.toHex()}")
            }
            response
        }

    companion object {
        private fun huami(short: Int) = UUID.fromString("%08x-0000-3512-2118-0009af100700".format(short))
        private fun sig(short: Int) = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))

        val SENSOR = huami(0x0001)
        val FETCH_CONTROL = huami(0x0004)
        val FETCH_DATA = huami(0x0005)
        val BATTERY = huami(0x0006)
        val STEPS = huami(0x0007)
        val AUTH = huami(0x0009)
        val CHUNKED_WRITE = huami(0x0016)
        val CHUNKED_READ = huami(0x0017)
        val HR_MEASUREMENT = sig(0x2A37)
        val HR_CONTROL = sig(0x2A39)
        /** Software Revision String, verified on Mi Smart Band 6 (docs/ble-protocol.md). */
        val FIRMWARE_REVISION = sig(0x2A28)

        private const val MAX_ROUNDS = 30
        private const val RESPONSE_TIMEOUT_MS = 10_000L
        private const val FETCH_TIMEOUT_MS = 15_000L

        fun heartRateIntervalCommand(minutes: Int): ByteArray {
            require(minutes in 0..120) { "частота пульса должна быть от 0 до 120 минут" }
            return byteArrayOf(0x14, minutes.toByte())
        }

        fun encodeTime(time: ZonedDateTime): ByteArray = byteArrayOf(
            time.year.toByte(), (time.year shr 8).toByte(), time.monthValue.toByte(), time.dayOfMonth.toByte(),
            time.hour.toByte(), time.minute.toByte(), 0, (time.offset.totalSeconds / 900).toByte(),
        )

        fun decodeTime(data: ByteArray, offset: Int): ZonedDateTime {
            val zone = if (data.size > offset + 7) ZoneOffset.ofTotalSeconds(data[offset + 7] * 900) else ZoneOffset.UTC
            return ZonedDateTime.of(
                le16(data, offset), data[offset + 2].toInt(), data[offset + 3].toInt(),
                data[offset + 4].toInt(), data[offset + 5].toInt(), data[offset + 6].toInt(), 0, zone,
            )
        }

        internal fun decodeFirmware(data: ByteArray): String = data
            .toString(Charsets.UTF_8)
            .trim('\u0000', ' ', '\r', '\n', '\t')
            .ifEmpty { throw IOException("браслет вернул пустую версию прошивки") }
    }
}
