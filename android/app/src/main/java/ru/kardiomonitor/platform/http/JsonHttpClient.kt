package ru.kardiomonitor.platform.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ответ сервера с ошибкой (`application/problem+json`, backend/docs/api-contract.md).
 * [code] — стабильный машинный код, [message] — `title` и `detail` для журнала и экрана.
 */
class HttpProblemException(val status: Int, val code: String, message: String) : IOException(message)

/**
 * JSON поверх HTTP без сторонних библиотек: HttpURLConnection и org.json есть в Android
 * (ADR-0006). Все сбои — [IOException]: нет сети, таймаут, ответ 4xx/5xx, неверный JSON.
 */
class JsonHttpClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {
    /** GET; null — пустое тело ответа. */
    suspend fun get(url: String, bearerToken: String? = null): JSONObject? =
        request("GET", url, body = null, bearerToken)

    /** POST с JSON-телом; null — пустое тело ответа (204). */
    suspend fun post(url: String, body: JSONObject, bearerToken: String? = null): JSONObject? =
        request("POST", url, body, bearerToken)

    /** PUT с JSON-телом; null — пустое тело ответа. */
    suspend fun put(url: String, body: JSONObject, bearerToken: String? = null): JSONObject? =
        request("PUT", url, body, bearerToken)

    private suspend fun request(method: String, url: String, body: JSONObject?, bearerToken: String?): JSONObject? =
        withContext(Dispatchers.IO) {
            val connection = open(url, method, bearerToken)
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                readResponse(connection)
            } finally {
                connection.disconnect()
            }
        }

    private fun open(url: String, method: String, bearerToken: String?): HttpURLConnection {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("адрес не HTTP: $url")
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        // ngrok бесплатного плана показывает браузерам страницу-предупреждение; API она не нужна
        connection.setRequestProperty("ngrok-skip-browser-warning", "1")
        if (bearerToken != null) connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        return connection
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject? {
        val status = connection.responseCode
        val stream = if (status in SUCCESS) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in SUCCESS) throw problemOf(status, text)
        return if (text.isBlank()) null else parse(text)
    }

    private fun problemOf(status: Int, text: String): HttpProblemException {
        val problem = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return HttpProblemException(status, "http_error", "сервер ответил $status")
        }
        val title = problem.optString("title", "сервер ответил $status")
        val detail = problem.optString("detail").takeIf { it.isNotBlank() }
        return HttpProblemException(status, problem.optString("code", "http_error"), listOfNotNull(title, detail).joinToString(": "))
    }

    private fun parse(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw IOException("сервер вернул не JSON", e)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        val SUCCESS = 200..299
    }
}
