package com.phonetts.core.prefs

/**
 * The user's opt-in for long-document mode, over an injected [PreferenceStore] (mirrors
 * [PlaybackCuePreferences] / [FavoriteVoices]: `:core` holds the pure toggle, `:app` supplies the
 * SharedPreferences-backed store and the actual scratch file).
 *
 * When enabled, the app backs a generation's
 * [com.phonetts.core.audio.buffer.GeneratedAudio] with a
 * [com.phonetts.core.audio.buffer.ChunkSpill] so a book-length synthesis spills older audio chunks
 * to disk instead of holding everything in RAM (issue #34). Off by default: the in-RAM behaviour is
 * what makes instant pause/resume/replay cheapest, and only very long documents need the ceiling, so
 * ordinary use pays nothing.
 */
class LongDocumentPreferences(private val store: PreferenceStore) {
    /** Whether generated audio should spill to disk past the live window. Off by default. */
    fun enabled(): Boolean = store.getString(LONG_DOCUMENT_MODE_KEY) == TRUE

    /** Turns long-document (spill-to-disk) mode on or off. */
    fun setEnabled(enabled: Boolean) {
        store.putString(LONG_DOCUMENT_MODE_KEY, if (enabled) TRUE else FALSE)
    }

    companion object {
        private const val LONG_DOCUMENT_MODE_KEY = "long_document_spill_mode"
        private const val TRUE = "true"
        private const val FALSE = "false"
    }
}
