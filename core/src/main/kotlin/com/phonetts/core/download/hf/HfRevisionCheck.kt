package com.phonetts.core.download.hf

import kotlinx.serialization.json.Json

/**
 * Result of comparing an installed model's stored revision against the Hub's current one.
 * [currentRevision] is `null` when the Hub didn't report a sha for the repo; that fails closed to
 * "no update available" rather than guessing (spec rule 4 — never guess on missing signal).
 */
data class HfUpdateStatus(
    val modelId: String,
    val installedRevision: String,
    val currentRevision: String?,
    val updateAvailable: Boolean,
) {
    companion object {
        /** Builds the status by comparing [installedRevision] against [currentRevision]. */
        fun of(
            modelId: String,
            installedRevision: String,
            currentRevision: String?,
        ): HfUpdateStatus =
            HfUpdateStatus(
                modelId = modelId,
                installedRevision = installedRevision,
                currentRevision = currentRevision,
                updateAvailable = currentRevision != null && currentRevision != installedRevision,
            )
    }
}

/**
 * Checks whether a model's stored commit is stale against the Hugging Face Hub. Where the stored
 * commit itself is persisted (SharedPreferences, a manifest sidecar, ...) is an `:app` concern —
 * this is the pure fetch-current-commit + compare half of that flow, testable via the injected
 * [HttpClient] and fixture JSON with no network involved.
 */
class HfRevisionCheck(
    private val http: HttpClient,
    private val json: Json = HfCatalog.defaultJson,
) {
    /** Fetches the repo's current commit sha and compares it with [installedRevision]. */
    fun check(
        modelId: String,
        installedRevision: String,
    ): HfUpdateStatus {
        val current = fetchModelInfo(modelId).sha
        return HfUpdateStatus.of(modelId, installedRevision, current)
    }

    /** Fetches and parses the repo's current model info, without comparing anything. */
    fun fetchModelInfo(modelId: String): HfModelInfo {
        val body = http.getText(HfEndpoints.modelInfoUrl(modelId), HfCatalog.USER_AGENT)
        return json.decodeFromString(HfModelInfo.serializer(), body)
    }
}
