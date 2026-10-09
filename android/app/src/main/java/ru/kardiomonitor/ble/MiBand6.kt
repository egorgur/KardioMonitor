package ru.kardiomonitor.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

class AuthException(message: String) : IOException(message)

/** Запись за одну минуту из истории браслета. */
data class ActivitySample(
    val epochSecond: Long,
    val heartRate: Int?,
    val steps: Int,
    val kind: Int,
    val intensity: Int,
)

/**
 * Протокол Mi Band 6 поверх GattClient. Порт miband6_poc.py и fetch_history.py.
 * [scope] нужен для фоновых подтверждений (ack) сообщений канала 0x0017.
 */
class MiBand6(private val gatt: GattClient, private val scope: CoroutineScope) {

    // ---------- Авторизация ----------

    suspend fun authenticate(key: ByteArray) {
        if (gatt.hasCharacteristic(CHUNKED_WRITE) && gatt.hasCharacteristic(CHUNKED_READ)) {
            authenticate2021(key)
        } else {
            authenticateLegacy(key)
        }
    }

    private suspend fun authenticate2021(key: ByteArray) {
        val messages = Channel<ChunkedDecoder.Message>(Channel.UNLIMITED)
        val decoder = ChunkedDecoder()
        // Канал остаётся подписанным весь сеанс: браслет присылает сообщения и после авторизации
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

        suspend fun expect(command: Int): ByteArray = withTimeout(10_000) {
            while (true) {
                val m = messages.receive()
                val p = m.payload
                if (m.endpoint != ENDPOINT_AUTH || p.size < 3 || p[0] != 0x10.toByte() || p[1] != command.toByte()) {
                    continue
                }
                if (p[2] != 0x01.toByte()) throw AuthException(authError(p))
                return@withTimeout p
            }
            @Suppress("UNREACHABLE_CODE")
            ByteArray(0)
        }

        send(auth.pubkeyCommand())
        val challenge = expect(0x04)
        send(auth.answerChallenge(challenge))
        expect(0x05)
    }

    private suspend fun authenticateLegacy(key: ByteArray) {
        val responses = Channel<ByteArray>(Channel.UNLIMITED)
        gatt.enableNotifications(AUTH) { responses.trySend(it) }

        suspend fun expect(prefix: ByteArray): ByteArray = withTimeout(10_000) {
            val r = responses.receive()
            if (r.size < 3 || !r.copyOf(3).contentEquals(prefix)) throw AuthException(authError(r))
            r
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

    // ---------- Батарея, шаги, пульс ----------

    suspend fun readBattery(): Int = gatt.read(BATTERY)[1].toInt() and 0xFF

    suspend fun readSteps(): Int = parseSteps(gatt.read(STEPS))

    suspend fun subscribeSteps(onSteps: (Int) -> Unit) {
        gatt.enableNotifications(STEPS) { onSteps(parseSteps(it)) }
    }

    private fun parseSteps(data: ByteArray) = if (data.size >= 5) le32(data, 1) else 0

    suspend fun startHeartRate(onValue: (Int) -> Unit) {
        gatt.enableNotifications(HR_MEASUREMENT) { data ->
            val bpm = if (data[0].toInt() and 0x01 != 0) le16(data, 1) else data[1].toInt() and 0xFF
            onValue(bpm)
        }
        gatt.write(HR_CONTROL, byteArrayOf(0x15, 0x02, 0x00)) // стоп ручного измерения
        gatt.write(HR_CONTROL, byteArrayOf(0x15, 0x01, 0x00)) // стоп непрерывного
        writeBestEffort(SENSOR, byteArrayOf(0x01, 0x03, 0x19))
        gatt.write(HR_CONTROL, byteArrayOf(0x15, 0x01, 0x01)) // старт непрерывного
        writeBestEffort(SENSOR, byteArrayOf(0x02))
    }

    /** Необязательная запись: сбой GATT пишется в журнал и не прерывает сценарий, отмена — прерывает. */
    private suspend fun writeBestEffort(uuid: UUID, data: ByteArray) {
        try {
            gatt.write(uuid, data)
        } catch (e: IOException) {
            Log.w(GattClient.TAG, "необязательная запись в $uuid не удалась", e)
        }
    }

    /** Без пинга раз в ~12 с браслет прекращает непрерывное измерение. */
    suspend fun pingHeartRate() = gatt.write(HR_CONTROL, byteArrayOf(0x16))

    suspend fun stopHeartRate() = gatt.write(HR_CONTROL, byteArrayOf(0x15, 0x01, 0x00))

    /**
     * Частота фонового измерения пульса, которое браслет пишет в историю (0 — выключено).
     * Та же настройка, что «Пульс → частота измерения» в Mi Fitness; Mi Fitness при
     * подключении может её перезаписать.
     */
    suspend fun setHeartRateInterval(minutes: Int) = gatt.write(HR_CONTROL, heartRateIntervalCommand(minutes))

    // ---------- История ----------

    /**
     * Выгружает поминутную историю начиная с [since]. Подтверждение 03 («данные получены»)
     * не отправляется — данные остаются на браслете и для Mi Fitness.
     */
    suspend fun fetchActivity(since: ZonedDateTime, onProgress: (String) -> Unit): List<ActivitySample> {
        val control = Channel<ByteArray>(Channel.UNLIMITED)
        val buffer = ByteArrayOutputStream()
        var lastCounter = -1
        var broken = false

        gatt.enableNotifications(FETCH_CONTROL) { control.trySend(it) }
        gatt.enableNotifications(FETCH_DATA) { data ->
            synchronized(buffer) {
                val counter = data[0].toInt() and 0xFF
                if (counter != (lastCounter + 1) and 0xFF) {
                    broken = true
                } else {
                    lastCounter = counter
                    buffer.write(data, 1, data.size - 1)
                }
            }
        }

        suspend fun expect(command: Int): ByteArray = withTimeout(15_000) {
            while (true) {
                val r = control.receive()
                if (r.size >= 3 && r[0] == 0x10.toByte() && r[1] == command.toByte()) {
                    if (r[2] != 0x01.toByte()) throw IOException("браслет отклонил команду $command: ${r.toHex()}")
                    return@withTimeout r
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ByteArray(0)
        }

        val samples = mutableListOf<ActivitySample>()
        var from = since
        repeat(MAX_ROUNDS) {
            synchronized(buffer) {
                buffer.reset()
                lastCounter = -1
                broken = false
            }
            gatt.write(FETCH_CONTROL, byteArrayOf(0x01, 0x01) + encodeTime(from))
            val response = expect(0x01)
            if (le32(response, 3) == 0) return samples
            val start = decodeTime(response, 7)

            gatt.write(FETCH_CONTROL, byteArrayOf(0x02))
            expect(0x02)
            val raw = synchronized(buffer) {
                if (broken) throw IOException("история пришла с пропусками пакетов")
                buffer.toByteArray()
            }
            val chunk = parseSamples(start, raw)
            onProgress("получено ${samples.size + chunk.size} мин")
            if (chunk.isEmpty()) return samples
            samples += chunk
            from = start.plusMinutes(chunk.size.toLong())
            if (!from.isBefore(ZonedDateTime.now())) return samples
        }
        return samples
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

        private const val MAX_ROUNDS = 30
        private const val SAMPLE_SIZE = 8 // Mi Band 6

        const val KIND_NOT_WORN = 115
        const val KIND_CHARGING = 118

        /** Как в Gadgetbridge (HuamiSupport.setHeartrateMeasurementInterval): 14 <минуты>, максимум 120. */
        fun heartRateIntervalCommand(minutes: Int): ByteArray {
            require(minutes in 0..120) { "частота пульса должна быть от 0 до 120 минут" }
            return byteArrayOf(0x14, minutes.toByte())
        }

        /** год(2) месяц день час минута, 0, смещение от UTC в четвертях часа. */
        fun encodeTime(t: ZonedDateTime): ByteArray = byteArrayOf(
            t.year.toByte(), (t.year shr 8).toByte(), t.monthValue.toByte(), t.dayOfMonth.toByte(),
            t.hour.toByte(), t.minute.toByte(), 0, (t.offset.totalSeconds / 900).toByte(),
        )

        fun decodeTime(data: ByteArray, offset: Int): ZonedDateTime {
            val zone = if (data.size > offset + 7) ZoneOffset.ofTotalSeconds(data[offset + 7] * 900) else ZoneOffset.UTC
            return ZonedDateTime.of(
                le16(data, offset), data[offset + 2].toInt(), data[offset + 3].toInt(),
                data[offset + 4].toInt(), data[offset + 5].toInt(), data[offset + 6].toInt(), 0, zone,
            )
        }

        /** 8 байт на минуту: kind, intensity, steps, heart_rate, ?, sleep, deep_sleep, rem_sleep. */
        fun parseSamples(start: ZonedDateTime, raw: ByteArray): List<ActivitySample> {
            val startSecond = start.toEpochSecond()
            return (0 until raw.size / SAMPLE_SIZE).map { i ->
                val o = i * SAMPLE_SIZE
                val hr = raw[o + 3].toInt() and 0xFF
                ActivitySample(
                    epochSecond = startSecond + i * 60L,
                    heartRate = hr.takeIf { it in 11..249 }, // 0 — не измерялся, 255 — ошибка
                    steps = raw[o + 2].toInt() and 0xFF,
                    kind = raw[o].toInt() and 0xFF,
                    intensity = raw[o + 1].toInt() and 0xFF,
                )
            }
        }
    }
}
