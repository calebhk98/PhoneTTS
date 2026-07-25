package com.phonetts.core.prefs

import com.phonetts.core.model.ModelDescriptor

/**
 * Where the user left off in one document: which engine/voice/speed synthesized it, and how far
 * in (by sentence index - the same unit the spec's sentence-chunked synthesis loop advances by,
 * spec §8). Purely a value the caller supplies; this file does not chunk or synthesize text.
 */
data class DocumentPosition(
    val documentId: String,
    val engineId: String,
    val voiceId: String,
    val speed: Float,
    val sentenceIndex: Int,
    /**
     * The catalog `modelId` active when this position was recorded (issue #147) - deliberately
     * separate from [engineId]: several sideloaded models can share one engine, so `engineId` alone
     * can't say which model a saved [voiceId] belonged to. Nullable and defaulted so every existing
     * positional call site (and every position saved before this field existed) keeps compiling and
     * resuming exactly as before - see [DocumentMemory.resume]'s unchanged fail-closed contract. A
     * position with no `modelId` simply isn't eligible for [DocumentMemory.preferredSelection]'s
     * per-document restore; it falls through to null there rather than guessing which model an
     * engine-only record meant.
     */
    val modelId: String? = null,
)

/**
 * One document's remembered model/voice/speed, already validated against a live catalog - the
 * return type of [DocumentMemory.preferredSelection]. Never constructed with a [descriptor] the
 * caller didn't itself pass in, so it can't smuggle a stale/fabricated model fact past the caller's
 * own catalog.
 */
data class DocumentVoicePreference(
    val descriptor: ModelDescriptor,
    val voiceId: String,
    val speed: Float,
)

/**
 * Per-document engine/voice/speed/position memory, over an injected [PreferenceStore] (mirrors
 * [com.phonetts.core.resolver.OverrideStore]'s pattern). This seam only persists and returns a
 * [DocumentPosition] - deciding whether text still needs re-synthesizing before resuming
 * playback at that position is the app's concern, not this class's.
 *
 * Issue #147 extends this from a resume-only seam into a genuine per-document voice/speed
 * preference via [preferredSelection]: opening a document restores the voice/model/speed it was
 * last used with, falling back to the caller's global last-used selection when nothing usable was
 * recorded for it.
 */
class DocumentMemory(private val store: PreferenceStore) {
    /** Records [position], overwriting any prior position saved for the same document. */
    fun record(position: DocumentPosition) {
        store.putString(fieldKey(position.documentId, FIELD_ENGINE), position.engineId)
        store.putString(fieldKey(position.documentId, FIELD_VOICE), position.voiceId)
        store.putString(fieldKey(position.documentId, FIELD_SPEED), position.speed.toString())
        store.putString(fieldKey(position.documentId, FIELD_SENTENCE), position.sentenceIndex.toString())
        val modelKey = fieldKey(position.documentId, FIELD_MODEL)
        val modelId = position.modelId
        if (modelId != null) store.putString(modelKey, modelId) else store.remove(modelKey)
    }

    /**
     * The last recorded position for [documentId], or null if nothing was ever recorded, or the
     * saved fields are incomplete/corrupt (fails closed rather than returning a partial resume).
     * [DocumentPosition.modelId] is optional even when the rest resolves - a record saved before
     * issue #147 (or a partial write of only the newer field) simply comes back with `modelId = null`
     * rather than invalidating the whole resume point.
     */
    fun resume(documentId: String): DocumentPosition? {
        val engineId = store.getString(fieldKey(documentId, FIELD_ENGINE)) ?: return null
        val voiceId = store.getString(fieldKey(documentId, FIELD_VOICE)) ?: return null
        val speed = store.getString(fieldKey(documentId, FIELD_SPEED))?.toFloatOrNull() ?: return null
        val sentenceIndex = store.getString(fieldKey(documentId, FIELD_SENTENCE))?.toIntOrNull() ?: return null
        val modelId = store.getString(fieldKey(documentId, FIELD_MODEL))
        return DocumentPosition(documentId, engineId, voiceId, speed, sentenceIndex, modelId)
    }

    /**
     * The voice/model/speed to restore when [documentId] is opened (issue #147), validated against
     * the CURRENTLY available [models] - fails closed exactly the way
     * [com.phonetts.core.prefs.LastUsedSelectionStore]'s caller already validates its own saved
     * selection: a saved `modelId` no longer present in [models] (the model/engine was deleted or a
     * sideload was removed) returns null so the caller falls back to the GLOBAL last-used selection
     * instead of leaving the user stuck pointed at a model that no longer exists. A position with no
     * recorded `modelId` at all (a legacy resume-only record, or a plain engine-only write from
     * before this field existed) ALSO returns null for the same reason: `engineId` alone can't say
     * which of possibly several models sharing that engine it meant, so guessing one would be
     * exactly the kind of untrusted assumption rule 4 (`inspect()` fails closed) forbids elsewhere.
     *
     * Once the model itself is confirmed to still exist, a saved [DocumentPosition.voiceId] that no
     * longer belongs to it falls back to that model's own default voice (never null - a model always
     * has one), and a saved speed outside the model's current range falls back to its default speed;
     * only a missing MODEL fails all the way back to the global default.
     */
    fun preferredSelection(
        documentId: String,
        models: List<ModelDescriptor>,
    ): DocumentVoicePreference? {
        val saved = resume(documentId) ?: return null
        val modelId = saved.modelId ?: return null
        val descriptor = models.firstOrNull { it.modelId == modelId } ?: return null
        val voiceId = descriptor.voices.firstOrNull { it.id == saved.voiceId }?.id ?: descriptor.defaultVoiceId
        val speed = saved.speed.takeIf { it in descriptor.speedRange } ?: descriptor.defaultSpeed
        return DocumentVoicePreference(descriptor, voiceId, speed)
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
        private const val FIELD_MODEL = "modelId"
        private val FIELDS = listOf(FIELD_ENGINE, FIELD_VOICE, FIELD_SPEED, FIELD_SENTENCE, FIELD_MODEL)
    }
}
