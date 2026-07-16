package com.phonetts.core.prefs

/**
 * Where the user left off in one document: which engine/voice/speed synthesized it, and how far
 * in (by sentence index — the same unit the spec's sentence-chunked synthesis loop advances by,
 * spec §8). Purely a value the caller supplies; this file does not chunk or synthesize text.
 */
data class DocumentPosition(
    val documentId: String,
    val engineId: String,
    val voiceId: String,
    val speed: Float,
    val sentenceIndex: Int,
)

/**
 * Per-document engine/voice/speed/position memory, over an injected [PreferenceStore] (mirrors
 * [com.phonetts.core.resolver.OverrideStore]'s pattern). This seam only persists and returns a
 * [DocumentPosition] — deciding whether text still needs re-synthesizing before resuming
 * playback at that position is the app's concern, not this class's.
 */
class DocumentMemory(private val store: PreferenceStore) {
    /** Records [position], overwriting any prior position saved for the same document. */
    fun record(position: DocumentPosition) {
        store.putString(fieldKey(position.documentId, FIELD_ENGINE), position.engineId)
        store.putString(fieldKey(position.documentId, FIELD_VOICE), position.voiceId)
        store.putString(fieldKey(position.documentId, FIELD_SPEED), position.speed.toString())
        store.putString(fieldKey(position.documentId, FIELD_SENTENCE), position.sentenceIndex.toString())
    }

    /**
     * The last recorded position for [documentId], or null if nothing was ever recorded, or the
     * saved fields are incomplete/corrupt (fails closed rather than returning a partial resume).
     */
    fun resume(documentId: String): DocumentPosition? {
        val engineId = store.getString(fieldKey(documentId, FIELD_ENGINE)) ?: return null
        val voiceId = store.getString(fieldKey(documentId, FIELD_VOICE)) ?: return null
        val speed = store.getString(fieldKey(documentId, FIELD_SPEED))?.toFloatOrNull() ?: return null
        val sentenceIndex = store.getString(fieldKey(documentId, FIELD_SENTENCE))?.toIntOrNull() ?: return null
        return DocumentPosition(documentId, engineId, voiceId, speed, sentenceIndex)
    }

    /** Forgets any saved position for [documentId]. A no-op if none was recorded. */
    fun forget(documentId: String) {
        FIELDS.forEach { field -> store.remove(fieldKey(documentId, field)) }
    }

    private fun fieldKey(
        documentId: String,
        field: String,
    ) = "$KEY_PREFIX$documentId.$field"

    companion object {
        private const val KEY_PREFIX = "document_position."
        private const val FIELD_ENGINE = "engineId"
        private const val FIELD_VOICE = "voiceId"
        private const val FIELD_SPEED = "speed"
        private const val FIELD_SENTENCE = "sentenceIndex"
        private val FIELDS = listOf(FIELD_ENGINE, FIELD_VOICE, FIELD_SPEED, FIELD_SENTENCE)
    }
}
