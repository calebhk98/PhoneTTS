package com.phonetts.core.prefs

/**
 * The user's chosen font scale for the reading/editing text field (the A− / A+ control), persisted
 * over an injected [PreferenceStore] exactly like [DefaultVoicePreference] and [DocumentMemory]. This is a
 * pure display preference - it scales nothing about synthesis and touches no model fact - so the
 * scale bounds live here as ordinary named constants, not in any descriptor.
 */
class ReadingTextPreferences(private val store: PreferenceStore) {
    /** The saved scale, clamped into range; [DEFAULT_SCALE] when nothing (or something bad) is stored. */
    fun scale(): Float = store.getString(KEY)?.toFloatOrNull()?.coerceIn(MIN_SCALE, MAX_SCALE) ?: DEFAULT_SCALE

    /** Persists [scale], clamped into [[MIN_SCALE], [MAX_SCALE]], and returns the value actually stored. */
    fun setScale(scale: Float): Float {
        val clamped = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        store.putString(KEY, clamped.toString())
        return clamped
    }

    /** One A+ step larger (clamped), persisted; returns the new scale. */
    fun increased(): Float = setScale(scale() + STEP)

    /** One A− step smaller (clamped), persisted; returns the new scale. */
    fun decreased(): Float = setScale(scale() - STEP)

    companion object {
        const val DEFAULT_SCALE = 1.0f
        const val MIN_SCALE = 0.8f
        const val MAX_SCALE = 2.0f
        const val STEP = 0.1f
        private const val KEY = "reading_text.scale"
    }
}
