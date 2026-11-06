package com.phonetts.core.prefs

/**
 * The user's opt-in for long-document mode, over an injected [PreferenceStore] (mirrors
 * [PlaybackCuePreferences] / [DefaultVoicePreference]: `:core` holds the pure toggle, `:app` supplies the
 * SharedPreferences-backed store and the actual scratch file).
 *
 * The reader always spills older generated audio to disk. Enabling this preference reduces its
 * resident window from two minutes to thirty seconds; it never disables the memory ceiling.
 */
class LongDocumentPreferences(private val store: PreferenceStore) {
    /** Whether the reader should use its smaller resident audio window. Off by default. */
    fun enabled(): Boolean = store.getString(LONG_DOCUMENT_MODE_KEY) == TRUE

    /** Selects the smaller resident audio window. */
    fun setEnabled(enabled: Boolean) {
        store.putString(LONG_DOCUMENT_MODE_KEY, if (enabled) TRUE else FALSE)
    }

    companion object {
        private const val LONG_DOCUMENT_MODE_KEY = "long_document_spill_mode"
        private const val TRUE = "true"
        private const val FALSE = "false"
    }
}
