package ru.kardiomonitor.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Ждёт ответа браслета не дольше [timeoutMs]. Таймаут — сбой связи ([IOException]), а не
 * отмена: `TimeoutCancellationException` из `withTimeout` проскочил бы через
 * `catch (e: CancellationException) { throw e }` и молча завершил корутину вместо показа
 * ошибки (coding-rules.md §7).
 */
suspend fun <T : Any> withResponseTimeout(
    timeoutMs: Long,
    operation: String,
    block: suspend CoroutineScope.() -> T,
): T = withTimeoutOrNull(timeoutMs, block) ?: throw IOException("браслет не ответил за $timeoutMs мс: $operation")
