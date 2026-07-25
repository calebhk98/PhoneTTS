package com.phonetts.app.speech

import com.phonetts.app.AppGraph
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.store.FavoriteVoiceRef

/**
 * Cross-model voice favorites (issue #119), over the single [com.phonetts.core.store.FavoritesStore]
 * source of truth - split out of `TtsViewModel` so a favorites-only change never touches the same
 * file as playback/export/sideload (mission: break up the god object).
 */
class FavoritesController(private val graph: AppGraph) {
    /** The current favorited (modelId, voiceId) pairs, straight from the shared store. */
    fun refs(): List<FavoriteVoiceRef> = graph.favoritesStore.favoriteVoices()

    /** Flip [voiceId]'s favorite state for [modelId] and return the refreshed list. Favoriting is a
     * bookmark, not a selection - callers must never also switch the active model/voice from this. */
    fun toggle(
        modelId: String,
        voiceId: String,
    ): List<FavoriteVoiceRef> {
        graph.favoritesStore.toggleFavoriteVoice(modelId, voiceId)
        return refs()
    }

    /**
     * One-time port of the old model-blind voice favorites (a bare-voiceId SharedPreferences set)
     * into the model-aware store, so unifying on one favorites store never silently drops the
     * user's existing stars. A bare legacy id is matched against every [models] that actually offers
     * a voice with that id (fail closed: an id no model offers is dropped). No-op once the legacy
     * set is empty (already migrated) or before the catalog has any models to resolve against.
     */
    fun migrateLegacyFavorites(models: List<ModelDescriptor>) {
        val legacyIds = graph.defaultVoicePreference.legacyFavoriteVoiceIds()
        if (legacyIds.isEmpty() || models.isEmpty()) return
        legacyIds.forEach { voiceId ->
            models.filter { model -> model.voices.any { it.id == voiceId } }
                .forEach { model -> graph.favoritesStore.setFavoriteVoice(model.modelId, voiceId, favorite = true) }
        }
        graph.defaultVoicePreference.clearLegacyFavorites()
    }
}
