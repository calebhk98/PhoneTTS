package com.phonetts.core.prefs

import com.phonetts.core.engine.Voice

/**
 * The per-language default voice, over an injected [PreferenceStore] (mirrors the
 * [com.phonetts.core.resolver.Resolver] / [com.phonetts.core.resolver.OverrideStore] split:
 * `:core` holds the pure logic, `:app` supplies the SharedPreferences-backed store).
 *
 * Every method here takes a [Voice], never a bare id string - callers must source that [Voice]
 * from `descriptor.voices` (spec §5.7, SSOT: the descriptor is the sole authority for voices).
 * This class only persists the user's *choice* among those voices; it never invents one.
 *
 * Voice *favorites* used to live here too, keyed on a bare voice id with no model, in a separate
 * SharedPreferences set. That was a second, model-blind favorites store that diverged from the
 * model-aware [com.phonetts.core.store.FavoritesStore] (issue: favorites not a single source of
 * truth). Favorites now live solely in `FavoritesStore`; the only favorites logic left here is
 * [legacyFavoriteVoiceIds] / [clearLegacyFavorites], used once to migrate the old set forward.
 */
class DefaultVoicePreference(private val store: PreferenceStore) {
    /** The saved default voice id for [language], or null if none has been set. */
    fun defaultVoiceId(language: String): String? = store.getString(defaultKey(language))

    /** Records [voice] as the default for its own [Voice.language]. */
    fun setDefaultVoice(voice: Voice) {
        store.putString(defaultKey(voice.language), voice.id)
    }

    /**
     * The saved default voice for [language] among [voices] (sourced from a descriptor), or null
     * if none is saved, or the saved id is no longer among [voices] (e.g. the model changed).
     * Fails closed rather than returning a voice the caller didn't offer.
     */
    fun defaultVoice(
        language: String,
        voices: List<Voice>,
    ): Voice? {
        val savedId = defaultVoiceId(language) ?: return null
        return voices.firstOrNull { it.id == savedId }
    }

    /**
     * The legacy (pre-unification) favorited voice ids: bare voice ids with no model, from the old
     * SharedPreferences set. Read once to migrate into [com.phonetts.core.store.FavoritesStore],
     * then dropped via [clearLegacyFavorites]. Empty once migration has run.
     */
    fun legacyFavoriteVoiceIds(): Set<String> = store.getStringSet(LEGACY_FAVORITES_KEY)

    /** Clears the legacy favorites set so the one-time migration never re-runs. */
    fun clearLegacyFavorites() {
        store.remove(LEGACY_FAVORITES_KEY)
    }

    private fun defaultKey(language: String) = "$DEFAULT_VOICE_PREFIX$language"

    companion object {
        private const val LEGACY_FAVORITES_KEY = "favorite_voice_ids"
        private const val DEFAULT_VOICE_PREFIX = "default_voice_lang_"
    }
}
