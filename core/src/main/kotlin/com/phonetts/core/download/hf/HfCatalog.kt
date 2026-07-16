package com.phonetts.core.download.hf

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Reads the Hugging Face Hub over an injected [HttpClient]: search text-to-speech models, and list
 * a repo's files. All the parsing/URL logic is pure, so this is unit-tested with a fake client and
 * fixture JSON — no network in tests.
 */
class HfCatalog(
    private val http: HttpClient,
    private val json: Json = defaultJson,
) {
    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<HfModelSummary> {
        val body = http.getText(HfEndpoints.searchModelsUrl(query, limit), USER_AGENT)
        return json.decodeFromString(ListSerializer(HfModelSummary.serializer()), body)
    }

    fun listFiles(
        modelId: String,
        revision: String = DEFAULT_REVISION,
    ): List<HfTreeEntry> {
        val body = http.getText(HfEndpoints.treeUrl(modelId, revision), USER_AGENT)
        return json.decodeFromString(ListSerializer(HfTreeEntry.serializer()), body)
    }

    companion object {
        const val DEFAULT_LIMIT = 30
        const val DEFAULT_REVISION = "main"

        // HF asks clients to send a descriptive User-Agent; anonymous access is fine for public repos.
        val USER_AGENT = mapOf("User-Agent" to "PhoneTTS/0.1 (offline-tts-android)")

        val defaultJson = Json { ignoreUnknownKeys = true }
    }
}
