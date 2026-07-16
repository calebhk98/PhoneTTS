package com.phonetts.app.hf

import com.phonetts.core.download.hf.HttpClient
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The [HttpClient] the [com.phonetts.core.download.hf.HfCatalog] uses on device, over
 * HttpURLConnection (available on Android; no third-party HTTP dependency). Throws on non-2xx.
 * All the parsing/URL logic it feeds is in :core and unit-tested; this class is just transport.
 */
class HttpUrlConnectionClient(
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : HttpClient {
    override fun getText(url: String, headers: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val code = connection.responseCode
            if (code !in HTTP_OK_RANGE) throw IOException("HTTP $code for $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000
        private val HTTP_OK_RANGE = 200..299
    }
}
