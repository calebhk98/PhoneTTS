package com.phonetts.core.prefs

/**
 * The user's most recently used model + voice + speed (issue #19-1), remembered GLOBALLY rather
 * than per document - the owner's call: regenerating the exact same document is rare, so this is
 * one shared "where I left off" rather than [DocumentMemory]'s per-document resume position.
 *
 * Keyed by [modelId] (not `engineId`): `modelId` is the field that uniquely identifies one entry
 * in the catalog's model list (spec: several sideloaded models can share one engine), and it is
 * exactly what the restore step needs to look the [com.phonetts.core.model.ModelDescriptor] back
 * up in that list - `engineId` alone couldn't do that.
 */
data class LastUsedSelection(
    val modelId: String,
    val voiceId: String,
    val speed: Float,
)

/**
 * Persists/reads the single global [LastUsedSelection] over an injected [PreferenceStore] (mirrors
 * [FavoriteVoices]/[DocumentMemory]'s pattern: `:core` holds the pure logic, `:app` supplies the
 * SharedPreferences-backed store). Purely a value seam - it is the caller's job to decide whether a
 * saved modelId/voiceId still exist before trusting them as the initial UI selection (fail-closed:
 * a stale or corrupt save must never crash or select something that no longer exists).
 */
class LastUsedSelectionStore(private val store: PreferenceStore) {
    /** Records [selection], overwriting whatever was previously saved. */
    fun record(selection: LastUsedSelection) {
        store.putString(FIELD_MODEL, selection.modelId)
        store.putString(FIELD_VOICE, selection.voiceId)
        store.putString(FIELD_SPEED, selection.speed.toString())
    }

    /**
     * The last-saved selection, or null if nothing was ever saved or a saved field is missing/corrupt
     * (fails closed rather than returning a partial selection).
     */
    fun last(): LastUsedSelection? {
        val modelId = store.getString(FIELD_MODEL) ?: return null
        val voiceId = store.getString(FIELD_VOICE) ?: return null
        val speed = store.getString(FIELD_SPEED)?.toFloatOrNull() ?: return null
        return LastUsedSelection(modelId, voiceId, speed)
    }

    /** Forgets the saved selection, if any. */
    fun clear() {
        store.remove(FIELD_MODEL)
        store.remove(FIELD_VOICE)
        store.remove(FIELD_SPEED)
    }

    private companion object {
        const val FIELD_MODEL = "last_used.modelId"
        const val FIELD_VOICE = "last_used.voiceId"
        const val FIELD_SPEED = "last_used.speed"
    }
}
