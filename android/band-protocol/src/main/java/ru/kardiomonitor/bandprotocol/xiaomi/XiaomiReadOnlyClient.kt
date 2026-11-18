package ru.kardiomonitor.bandprotocol.xiaomi

import android.content.Context
import android.os.Build
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Read-only foundation только для M2345B1 / firmware 2.3.98, не полная поддержка Band 9.
 * open проверяет watch HMAC и точную модель/прошивку до доступа к чтению.
 * Нужны разрешения Bluetooth connect/scan; pairing, fallback, reconnect и записи отсутствуют.
 * Входной key — 16 декодированных байтов; копия очищается, оригинал очищает вызывающий код.
 * Любая ошибка/отмена закрывает сеанс. close безопасен из другого потока и идемпотентен.
 * Фонового reader нет: потеря связи определяется следующей операцией.
 * Сеанс ограничен 512 обычными кадрами и 8192 кадрами суммарно, включая history verification;
 * новый сеанс открывается только явным open.
 * SPP v2 использует заданный протоколом CTR без per-message MAC; CRC не заменяет MAC.
 */
class XiaomiReadOnlyClient internal constructor(
    private val createTransport: (String) -> XiaomiTransport,
    private val api: Int,
    private val openTimeoutMillis: Long = 15_000,
    private val readTimeoutMillis: Long = 6_000,
) : AutoCloseable {
    constructor(context: Context) : this(
        createTransport = { XiaomiRfcommTransport.create(context.applicationContext, it) },
        api = Build.VERSION.SDK_INT,
    )
    private val operations = Mutex()
    private val stateLock = Any()
    private var connection: XiaomiConnection? = null

    /** Однократное уведомление владельца об ошибке, таймауте или отмене чтения открытого сеанса.
     * Вызывается в контексте операции, должно быть быстрым. Не вызывается при close/ошибке open.
     * Фоновое обнаружение разрыва без следующей операции не выполняется.
     */
    @Volatile
    var onConnectionLost: (() -> Unit)? = null

    private class OpenProgress {
        @Volatile var phase = XiaomiOpenPhase.SOCKET_CONNECT
    }
    /** Требует существующий bond; неверная модель/прошивка отклоняется без доступного сеанса.
     * XiaomiOpenException содержит только фиксированный безопасный этап ошибки.
     */
    suspend fun open(address: String, key: ByteArray): Unit = operations.withLock {
        close()
        if (key.size != 16 || !address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) {
            throw IOException("Некорректные параметры подключения Xiaomi")
        }
        val secret = key.copyOf()
        val next = XiaomiConnection()
        val progress = OpenProgress()
        synchronized(stateLock) { connection = next }
        try {
            operation(next, openTimeoutMillis, notifyConnectionLost = false) {
                val transport = createTransport(address)
                next.attach(transport)
                transport.connect()
                val session = XiaomiSession(transport.input, transport.output)
                next.attach(session)
                session.open(secret, api) { progress.phase = it }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            throw XiaomiOpenException(progress.phase)
        } finally { secret.fill(0) }
    }
    /** null — уровень отсутствует в ответе; 0 означает настоящий нулевой заряд. */
    suspend fun readBattery(): Int? = operations.withLock {
        val current = current()
        operation(current, readTimeoutMillis) { current.session().battery() }
    }
    /** Только GET 8/10, без списка допустимых интервалов и без SET. */
    suspend fun readHeartRateSettings(): XiaomiHeartRateSettings = operations.withLock {
        val current = current()
        operation(current, readTimeoutMillis) { current.session().heartRateSettings() }
    }
    /** Отдельная проверка одного past-файла, не ActivitySource и не клинический импорт.
     * offset относится только к свежему индексу. Один общий таймаут на весь read/retention,
     * без продления на второй fetch; unknown time/value и полное покрытие не сертифицируются.
     */
    suspend fun verifyPastHistory(detailOffset: Int, repeat: Boolean = false): XiaomiHistoryVerification =
        verifyHistory(detailOffset, repeat, null)

    /** Opt-in сверка одной независимой точки; публичный aggregate-путь её не выполняет. */
    internal suspend fun verifyPastHistoryReference(
        detailOffset: Int,
        reference: XiaomiHistoryReference,
        selectReferenceTime: Boolean = false,
        diagnostics: XiaomiHistoryDiagnostics? = null,
        source: XiaomiHistoryIndex = XiaomiHistoryIndex.PAST,
    ): XiaomiHistoryVerification = verifyHistory(detailOffset, true, reference, selectReferenceTime, diagnostics, source)

    /** Отдельный reverse-order experiment; caller обязан очистить sample в finally. */
    internal suspend fun captureHistoryReferenceSample(
        detailOffset: Int,
        sample: XiaomiHistoryReferenceSample,
        source: XiaomiHistoryIndex,
        latestTarget: XiaomiHistoryLatestTarget? = null,
    ): XiaomiHistoryVerification = verifyHistory(detailOffset, true, null, source = source, sample = sample, latestTarget = latestTarget)

    private suspend fun verifyHistory(
        detailOffset: Int,
        repeat: Boolean,
        reference: XiaomiHistoryReference?,
        selectReferenceTime: Boolean = false,
        diagnostics: XiaomiHistoryDiagnostics? = null,
        source: XiaomiHistoryIndex = XiaomiHistoryIndex.PAST,
        sample: XiaomiHistoryReferenceSample? = null,
        latestTarget: XiaomiHistoryLatestTarget? = null,
    ): XiaomiHistoryVerification {
        require(detailOffset in 0..255) { "Некорректная позиция файла истории" }
        return operations.withLock {
            val current = current()
            operation(current, minOf(30_000, readTimeoutMillis * 5)) {
                current.session().verifyPastHistory(detailOffset, repeat, reference, selectReferenceTime, diagnostics, source, sample, latestTarget)
            }
        }
    }
    override fun close() {
        val current = synchronized(stateLock) {
            val value = connection
            connection = null
            value
        }
        current?.close()
    }
    private fun current(): XiaomiConnection = synchronized(stateLock) {
        connection ?: throw IOException("Сеанс Xiaomi не открыт")
    }
    private class Value<T>(val value: T)
    private suspend fun <T> operation(
        state: XiaomiConnection,
        timeout: Long,
        notifyConnectionLost: Boolean = true,
        block: () -> T,
    ): T {
        state.begin()
        try {
            val result = withTimeoutOrNull(timeout) { blocking(state, block) }
                ?: throw IOException("Истекло время ожидания Xiaomi")
            return result.value
        } catch (error: CancellationException) {
            invalidate(state, notifyConnectionLost)
            throw error
        } catch (_: Exception) {
            invalidate(state, notifyConnectionLost)
            throw IOException("Не удалось выполнить чтение Xiaomi")
        } finally { state.end() }
    }
    private fun invalidate(state: XiaomiConnection, notifyConnectionLost: Boolean) {
        val removed = synchronized(stateLock) {
            if (connection !== state) false else { connection = null; true }
        }
        state.close()
        if (removed && notifyConnectionLost) {
            // Исключение владельца не заменяет исходную ошибку и не нарушает отмену/cleanup.
            try { onConnectionLost?.invoke() } catch (_: Exception) { /* Сеанс уже закрыт. */ }
        }
    }
    private suspend fun <T> blocking(state: XiaomiConnection, block: () -> T): Value<T> = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { state.close() }
            launch(Dispatchers.IO) {
                try {
                    val result = Value(block())
                    if (continuation.isActive) continuation.resume(result)
                } catch (_: Exception) {
                    state.close()
                    if (continuation.isActive) continuation.resumeWithException(IOException("Ошибка связи Xiaomi"))
                }
            }
        }
    }
    override fun toString(): String = "XiaomiReadOnlyClient"
}
