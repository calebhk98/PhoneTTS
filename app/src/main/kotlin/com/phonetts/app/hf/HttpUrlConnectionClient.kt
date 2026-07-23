package com.phonetts.app.hf

import com.phonetts.core.download.hf.HfRateLimitHeader
import com.phonetts.core.download.hf.HfRateLimitedException
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
            // A 429 on the list/search path is an EXPECTED, self-resolving cooldown, not a real
            // error - surface it as the typed exception carrying HF's own reset hint (issue #103) so
            // the browse layer can show a countdown and auto-retry instead of flooding the error log.
            if (code == HTTP_TOO_MANY_REQUESTS) throw rateLimited(connection, url)
            if (code !in HTTP_OK_RANGE) throw IOException("HTTP $code for $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // HF sends no classic Retry-After; it sends `RateLimit: "api";r=<remaining>;t=<seconds>` on
    // every response, so parse `t=` as the authoritative wait (defensively also read Retry-After in
    // case a CDN edge adds one). A null hint tells the caller to fall back to exponential backoff.
    private fun rateLimited(
        connection: HttpURLConnection,
        url: String,
    ): HfRateLimitedException {
        val resetSeconds = HfRateLimitHeader.parseResetSeconds(connection.getHeaderField("RateLimit"))
        val retryAfterSeconds = HfRateLimitHeader.parseRetryAfterSeconds(connection.getHeaderField("Retry-After"))
        val seconds = resetSeconds ?: retryAfterSeconds
        return HfRateLimitedException(retryAfterMs = seconds?.let { it * MILLIS_PER_SECOND }, url = url)
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000
        private val HTTP_OK_RANGE = 200..299
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
