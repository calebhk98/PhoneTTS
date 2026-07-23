package com.phonetts.core.prefs

import com.phonetts.core.engine.BlendedVoiceSpec

/**
 * Persists the user's saved voice mixes ([BlendedVoiceSpec]s) over an injected [PreferenceStore],
 * mirroring [FavoriteVoices]: `:core` holds the pure logic, `:app` supplies the
 * SharedPreferences-backed store, so this stays unit-testable on a plain JVM. Only the recipe is
 * stored (the two source voice ids + weight), never audio or an embedding - the engine recomputes
 * the blend from the loaded model each time, so a saved mix survives app restarts and model
 * re-downloads as long as its source voices still exist.
 *
 * Each spec is encoded as one delimited record in a per-model string set keyed by [modelId], so
 * loading a model reads back exactly its mixes and nothing else. A malformed record (wrong field
 * count, unparseable weight) is skipped, not thrown - a corrupt entry can never crash load.
 */
class BlendedVoiceStore(private val store: PreferenceStore) {
    /** Every saved mix for [modelId], in no particular order. */
    fun forModel(modelId: String): List<BlendedVoiceSpec> = store.getStringSet(key(modelId)).mapNotNull(::decode)

    /**
     * Saves [spec] (adding it, or replacing any existing mix with the same [BlendedVoiceSpec.id]).
     * Returns the updated list for the spec's model.
     */
    fun save(spec: BlendedVoiceSpec): List<BlendedVoiceSpec> {
        val kept = forModel(spec.modelId).filterNot { it.id == spec.id }
        val updated = kept + spec
        store.putStringSet(key(spec.modelId), updated.map(::encode).toSet())
        return updated
    }

    /** Removes the mix with [voiceId] from [modelId], if present. Returns the updated list. */
    fun remove(
        modelId: String,
        voiceId: String,
    ): List<BlendedVoiceSpec> {
        val updated = forModel(modelId).filterNot { it.id == voiceId }
        store.putStringSet(key(modelId), updated.map(::encode).toSet())
        return updated
    }

    private fun encode(spec: BlendedVoiceSpec): String =
        listOf(spec.id, spec.name, spec.modelId, spec.voiceAId, spec.voiceBId, spec.weight.toString())
            .joinToString(FIELD_SEP.toString())

    private fun decode(record: String): BlendedVoiceSpec? {
        val parts = record.split(FIELD_SEP)
        if (parts.size != FIELD_COUNT) return null
        val weight = parts[WEIGHT_INDEX].toFloatOrNull() ?: return null
        return BlendedVoiceSpec(
            id = parts[0],
            name = parts[1],
            modelId = parts[2],
            voiceAId = parts[3],
            voiceBId = parts[4],
            weight = weight,
        )
    }

    private fun key(modelId: String) = "$KEY_PREFIX$modelId"

    private companion object {
        const val KEY_PREFIX = "blended_voices_"

        // Unit Separator (U+001F): a control char that never appears in a voice id/name/model id, so
        // records split unambiguously without escaping. A record missing a field is dropped on read.
        const val FIELD_SEP = '\u001F'
        const val FIELD_COUNT = 6
        const val WEIGHT_INDEX = 5
    }
}
