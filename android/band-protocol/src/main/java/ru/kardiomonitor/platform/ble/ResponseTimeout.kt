package ru.kardiomonitor.platform.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Таймаут ответа браслета остаётся ошибкой связи, а не отменой корутины. */
suspend fun <T : Any> withResponseTimeout(
    timeoutMs: Long,
    operation: String,
    block: suspend CoroutineScope.() -> T,
): T = withTimeoutOrNull(timeoutMs, block)
    ?: throw IOException("браслет не ответил за $timeoutMs мс: $operation")
