package ru.kardiomonitor.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ResponseTimeoutTest {

    @Test
    fun `браслет, не ответивший вовремя, даёт ошибку связи, а не отмену`() {
        runBlocking {
            try {
                withResponseTimeout(timeoutMs = 10, operation = "авторизация") { awaitCancellation() }
                fail("ожидалась ошибка связи")
            } catch (e: CancellationException) {
                fail("таймаут браслета не должен выглядеть как отмена: $e")
            } catch (e: IOException) {
                assertEquals("браслет не ответил за 10 мс: авторизация", e.message)
            }
        }
    }

    @Test
    fun `ответ, пришедший вовремя, возвращается без изменений`() {
        val answer = runBlocking { withResponseTimeout(timeoutMs = 1_000, operation = "чтение") { "10 05 01" } }
        assertEquals("10 05 01", answer)
    }
}
