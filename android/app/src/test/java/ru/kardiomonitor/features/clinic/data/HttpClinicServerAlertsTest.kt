package ru.kardiomonitor.features.clinic.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.clinic.domain.PatientName
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.UploadToken
import ru.kardiomonitor.platform.http.JsonHttpClient
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder

class HttpClinicServerAlertsTest {
    @Test
    fun `каждая страница запрашивает пять поддерживаемых видов и свой курсор`() = runBlocking {
        val requests = mutableListOf<URI>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/me/alerts") { exchange ->
            requests += exchange.requestURI
            val cursor = if (requests.size == 1) 17 else 25
            val body = """{"alerts":[],"cursor":$cursor,"has_more":${requests.size == 1}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = HttpClinicServer(JsonHttpClient())
            val registration = registration(server.address.port)
            val first = client.patientAlerts(registration, after = 0)
            val last = client.patientAlerts(registration, after = first.cursor)

            assertEquals(listOf("0", "17"), requests.map { query(it).getValue("after").single() })
            requests.forEach { assertEquals(SUPPORTED_KINDS, query(it).getValue("supported_kinds")) }
            assertEquals(25L, last.cursor)
            assertEquals(false, last.hasMore)
        } finally {
            server.stop(0)
        }
    }

    private fun registration(port: Int): ServerRegistration = ServerRegistration(
        ServerAddress.parse("http://127.0.0.1:$port"),
        "patient-test",
        PatientName("Пациент теста"),
        UploadToken("test-token"),
    )

    private fun query(uri: URI): Map<String, List<String>> = uri.rawQuery.split('&')
        .map { it.split('=', limit = 2) }
        .groupBy({ it[0] }, { URLDecoder.decode(it[1], Charsets.UTF_8.name()) })

    private companion object {
        val SUPPORTED_KINDS = listOf(
            "high_heart_rate", "daily_target_missed", "daily_target_met", "low_activity", "training_minutes_exceeded",
        )
    }
}
