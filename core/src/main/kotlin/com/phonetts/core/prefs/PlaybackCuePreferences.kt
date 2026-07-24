package com.phonetts.core.prefs

/**
 * The user's opt-in for playback cues, over an injected [PreferenceStore] (mirrors
 * [DefaultVoicePreference] / [DocumentMemory]: `:core` holds the pure logic, `:app` supplies the
 * SharedPreferences-backed store). Today the only cue is the end-of-document chime/haptic
 * (issue #32) - a short tone/vibration fired by the `:app` side's `PlaybackService` when a
 * playback flow completes *naturally* (as opposed to a user Stop).
 *
 * Kept a tiny value seam so the toggle is unit-testable on a plain JVM and so the actual
 * `Vibrator`/`SoundPool` work stays entirely `:app`-side. Defaults to **off**: a phone that makes
 * noise the user never asked for is worse than one that stays quiet, so the cue is opt-in.
 */
class PlaybackCuePreferences(private val store: PreferenceStore) {
    /** Whether the end-of-document chime/haptic should fire on natural completion. Off by default. */
    fun endOfDocumentCueEnabled(): Boolean = store.getString(END_OF_DOCUMENT_CUE_KEY) == TRUE

    /** Turns the end-of-document cue on or off. */
    fun setEndOfDocumentCueEnabled(enabled: Boolean) {
        store.putString(END_OF_DOCUMENT_CUE_KEY, if (enabled) TRUE else FALSE)
    }

    companion object {
        private const val END_OF_DOCUMENT_CUE_KEY = "playback_cue_end_of_document"
        private const val TRUE = "true"
        private const val FALSE = "false"
    }
}
