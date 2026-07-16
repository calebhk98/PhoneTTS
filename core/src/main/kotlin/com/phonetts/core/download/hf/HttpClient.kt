package com.phonetts.core.download.hf

/**
 * The HTTP transport seam. The catalog logic (search, list files) is pure and testable against a
 * fake client; the real implementation (HttpURLConnection with a User-Agent, on Android) lives in
 * :app. Implementations should throw on a non-2xx response.
 */
interface HttpClient {
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String
}
