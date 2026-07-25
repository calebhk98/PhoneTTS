package com.phonetts.core.diagnostics

/**
 * What the user was trying to do when a [Throwable] escaped, so [FailureExplainer] can lead with an
 * action-shaped headline ("Couldn't load this model" vs "Couldn't play that") instead of a bare
 * exception dump (issue #136 - `"Load failed: ${e.message}"` and five siblings were the ONLY feedback
 * a failure ever gave the user).
 *
 * Deliberately small and closed - a new failure surface picks the existing context that best matches
 * what the user asked for; it does not get a bespoke enum value of its own unless none of these fit.
 */
enum class FailureContext(internal val headline: String) {
    /** Loading a model's weights into an engine (`EngineManager.switchTo`/`engine.load()`). */
    LOADING_MODEL("Couldn't load this model"),

    /** Running text through the one generation path (`VoiceEngine.synthesize`), not counting export. */
    GENERATING("Couldn't generate audio"),

    /** Draining generated audio into the playback sink. */
    PLAYING("Couldn't play that"),

    /** Encoding generated audio out to a file (WAV/MP3/etc). */
    EXPORTING("Couldn't export audio"),

    /** Fetching bytes over the network (Hugging Face browse/download). */
    DOWNLOADING("Couldn't download that"),

    /** Bringing something from outside the app in - a sideloaded model bundle or an imported text file. */
    IMPORTING("Couldn't import that"),

    /** Managing an already-installed model - delete, manually assign an engine, change storage. */
    MANAGING_MODEL("Couldn't complete that"),
}
