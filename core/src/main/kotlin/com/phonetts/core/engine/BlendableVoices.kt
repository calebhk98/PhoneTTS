package com.phonetts.core.engine

/**
 * A user-defined voice built by blending two of a model's existing voices at a [weight] (issue #42).
 * This is the persisted, model-agnostic description of a mix - the recipe, not the audio: it names
 * the two source voices and how far to interpolate between them, so the same in-between timbre can
 * be rebuilt whenever the model is loaded (the actual embedding is recomputed by the engine, never
 * stored). [modelId] scopes a spec to the model whose voices it references, so mixes made for one
 * model never leak into another.
 *
 * [weight] is a fraction toward [voiceBId] in `[VoiceBlend.MIN_WEIGHT, VoiceBlend.MAX_WEIGHT]`:
 * 0 = pure [voiceAId], 1 = pure [voiceBId].
 */
data class BlendedVoiceSpec(
    val id: String,
    val name: String,
    val modelId: String,
    val voiceAId: String,
    val voiceBId: String,
    val weight: Float,
)

/**
 * The capability an engine implements when its graph accepts a CONTINUOUS speaker/style vector and
 * can therefore mix two of its voices into an in-between one (issue #42). Detecting it with
 * `engine is BlendableVoices` is polymorphism, not a banned `when(modelType)` switch (rule 5): the
 * engine advertises the same fact declaratively via
 * [com.phonetts.core.model.ModelDescriptor.supportsVoiceBlend], which is what the UI reads.
 * Models that pick a voice by a discrete id (MeloTTS `sid`, Piper's per-voice graphs, CosyVoice2's
 * native runtime) simply do not implement this.
 */
interface BlendableVoices {
    /**
     * Register a new selectable voice whose embedding is [VoiceBlend.blend] of the two source
     * voices named in [spec]. Returns the created [Voice] (now included in [VoiceEngine.voices]),
     * or null if either source voice id is unknown to the loaded model - fail closed rather than
     * inventing a voice. Must be called after the engine is loaded, when its voice embeddings exist.
     */
    fun addBlendedVoice(spec: BlendedVoiceSpec): Voice?
}
