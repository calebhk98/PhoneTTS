package com.phonetts.core.download.hf

/**
 * Serves canned response bodies keyed by a substring of the requested URL. Shared by the HF
 * download/catalog/revision tests so the fake HTTP client lives in exactly one place.
 */
internal class FakeHttpClient(private val routes: List<Pair<String, String>>) : HttpClient {
    val requested = mutableListOf<String>()

    override fun getText(
        url: String,
        headers: Map<String, String>,
    ): String {
        requested.add(url)
        return routes.firstOrNull { url.contains(it.first) }?.second
            ?: error("no fake route for $url")
    }
}
