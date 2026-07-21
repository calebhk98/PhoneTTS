package com.phonetts.core.automation

/**
 * The parsed, typed form of one automation intent (issue #41): a single object that carries every
 * caller-supplied field so the rest of the pipeline threads THIS around instead of five separate
 * parameters (owner's request on the issue). The Android entry point extracts the raw intent extras
 * once, builds this via [of], and hands it to [AutomationPlanner] — this type stays pure JVM so the
 * validation/normalization is proven by a `:core` test with no Android SDK.
 *
 * Fields mirror the documented intent extras: [text], [engineId], [voiceId], [speed], [outputUri].
 * Everything but [text] is optional — an absent [engineId]/[voiceId]/[speed] means "use the model's
 * default", resolved against the catalog by [AutomationPlanner]. Blank strings are normalized to
 * null in [of] so an empty extra is treated as "unset", never a lookup for the empty id.
 */
data class AutomationRequest(
    val text: String,
    val engineId: String? = null,
    val voiceId: String? = null,
    val speed: Float? = null,
    val outputUri: String? = null,
) {
    companion object {
        /**
         * Normalize raw extra values into a request: trim [text], and collapse blank optional
         * strings to null so "unset" and "empty" are the same thing (fail-closed lookups later).
         */
        fun of(
            text: String?,
            engineId: String?,
            voiceId: String?,
            speed: Float?,
            outputUri: String?,
        ): AutomationRequest =
            AutomationRequest(
                text = text?.trim().orEmpty(),
                engineId = engineId?.trim()?.ifBlank { null },
                voiceId = voiceId?.trim()?.ifBlank { null },
                speed = speed,
                outputUri = outputUri?.trim()?.ifBlank { null },
            )
    }
}
