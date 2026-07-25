package com.phonetts.app.testing

import com.phonetts.core.download.hf.HttpClient

/**
 * A scripted [HttpClient] double for `:app` unit tests: every call to [getText] returns whatever
 * [respond] returns for that [url], so tests never touch the network. Defaults to returning the
 * same fixed body for any URL, which is all [com.phonetts.app.hf.HfBrowseViewModel]'s `search()`/
 * `loadMore()` need (they only ever fetch one logical resource - the search results page).
 */
class FakeHttpClient(private val respond: (String) -> String) : HttpClient {
    constructor(body: String) : this({ _ -> body })

    override fun getText(url: String, headers: Map<String, String>): String = respond(url)
}
