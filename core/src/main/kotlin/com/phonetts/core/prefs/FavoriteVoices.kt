package com.phonetts.core.prefs

import com.phonetts.core.engine.Voice

/**
 * Favorite voices plus a per-language default voice, over an injected [PreferenceStore] (mirrors
 * the [com.phonetts.core.resolver.Resolver] / [com.phonetts.core.resolver.OverrideStore] split:
 * `:core` holds the pure logic, `:app` supplies the SharedPreferences-backed store).
 *
 * Every method here takes a [Voice], never a bare id string - callers must source that [Voice]
 * from `descriptor.voices` (spec §5.7, SSOT: the descriptor is the sole authority for voices).
 * This class only persists the user's *choice* among those voices; it never invents one.
 */
class FavoriteVoices(private val store: PreferenceStore) {
    /** Every currently favorited voice id. */
    fun favoriteIds(): Set<String> = store.getStringSet(FAVORITES_KEY)

    fun isFavorite(voice: Voice): Boolean = voice.id in favoriteIds()

    /** Flips [voice]'s favorite state and returns the new state (true = now favorited). */
    fun toggleFavorite(voice: Voice): Boolean {
        val current = favoriteIds()
        val wasFavorite = voice.id in current
        val updated = if (wasFavorite) current - voice.id else current + voice.id
        store.putStringSet(FAVORITES_KEY, updated)
        return !wasFavorite
    }

    /** [voices] (sourced from a descriptor) filtered down to the favorited ones, input order kept. */
    fun favoritesOf(voices: List<Voice>): List<Voice> = voices.filter { it.id in favoriteIds() }

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

    private fun defaultKey(language: String) = "$DEFAULT_VOICE_PREFIX$language"

    companion object {
        private const val FAVORITES_KEY = "favorite_voice_ids"
        private const val DEFAULT_VOICE_PREFIX = "default_voice_lang_"
    }
}
