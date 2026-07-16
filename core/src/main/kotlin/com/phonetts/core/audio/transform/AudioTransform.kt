package com.phonetts.core.audio.transform

// A post-processing step applied to generated audio. Transforms are NON-DESTRUCTIVE: they are
// pure functions over a copy of the audio and never touch the raw buffer the audio came from, so
// the user can toggle any of them off and hear the original again (see TransformChain). They
// operate on the ordered list of sentence segments (chunks) rather than one flat array, because
// join-aware steps like crossfade need to know where one segment ends and the next begins.
interface AudioTransform {
    /** Stable id used to toggle this transform on/off in a [TransformChain] and in the UI. */
    val id: String

    /** Human-facing name (the UI reads this — no transform name is hardcoded in the UI). */
    val displayName: String

    /**
     * Return processed segments. MUST NOT mutate [segments] or the arrays inside it — return new
     * arrays so the caller's raw audio is preserved for re-listening with the transform disabled.
     */
    fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray>
}
