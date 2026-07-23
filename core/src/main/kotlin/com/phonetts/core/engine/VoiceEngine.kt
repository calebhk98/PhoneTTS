package com.phonetts.core.engine

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * An engine loads weights and runs inference for one model family (spec §5.1).
 * It NEVER references another engine. The text frontend and the runtime it uses are
 * internal details, not part of this interface - those vary hardest between models and
 * are kept out of the seam on purpose (spec §5.2, §5.3).
 */
interface VoiceEngine {
    val id: String
    val displayName: String

    /**
     * Probe: given a downloaded bundle, can I run it? Return a match with everything
     * needed to build a descriptor, or null.
     *
     * MUST FAIL CLOSED: null means "not mine," never a guess. If the bundle's family,
     * sample rate, or phonemization scheme cannot be established with confidence, return
     * null so the resolver drops to the user-pick fallback (spec §6.2).
     */
    fun inspect(bundle: ModelBundle): EngineMatch?

    /**
     * Build a best-effort match for a bundle the user has manually assigned to this engine.
     * Unlike [inspect] this never returns null: the user's choice is authoritative, so the
     * engine fills in its family defaults (single voice, default speed range, etc.) to make
     * the sideloaded model first-class (spec §6.2). Throws only if the bundle is structurally
     * unusable by this family (e.g. no weights file at all).
     */
    fun forcedMatch(bundle: ModelBundle): EngineMatch

    /** Pull weights into memory. Exactly one engine is loaded at a time (spec §5.5). */
    suspend fun load(descriptor: ModelDescriptor)

    /** Free weights. Called by the EngineManager when the user switches models. */
    fun unload()

    fun voices(): List<Voice>

    /**
     * The ONE generation path. Real-time playback and file export both consume this same flow
     * (spec §6.1). Emits audio in chunks so playback/writing can start before the whole utterance
     * is generated. [params] carries the chosen values for the model's declared
     * [com.phonetts.core.model.ModelDescriptor.parameters] - each routes to the model's native
     * parameter (speed → the native duration knob, etc.); output audio is NEVER resampled to change
     * speed (that shifts pitch, spec §1.1.3). An engine reads only the parameters it declared.
     */
    fun synthesize(
        text: String,
        voiceId: String,
        params: SynthesisParams,
    ): Flow<FloatArray>

    /**
     * Convenience for the common "just a speed" call (keeps the many existing call sites terse).
     * Delegates to the [params] path with only speed set - every other declared parameter takes its
     * default.
     */
    fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
    ): Flow<FloatArray> = synthesize(text, voiceId, SynthesisParams.ofSpeed(speed))
}
