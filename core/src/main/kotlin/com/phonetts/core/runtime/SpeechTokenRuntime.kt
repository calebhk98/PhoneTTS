package com.phonetts.core.runtime

/**
 * The second, LLM-style inference backend the spec anticipated (§5.3): a runtime that decodes a
 * variable-length sequence of *speech token ids* autoregressively, rather than running a single
 * named-tensor forward pass. CosyVoice2's core is a Qwen2-0.5B decoder with a speech-token head
 * and a repeat-aware sampler; on-device that is a llama.cpp / ggml GGUF loop
 * (docs/research/cosyvoice2-mobile.md), which is emphatically NOT ONNX.
 *
 * It is still a [Runtime] so it registers in the same
 * [com.phonetts.core.registry.RuntimeRegistry] alongside the ONNX backend and an engine looks it
 * up by id exactly the same way (Runtime's own KDoc calls out "a different, LLM-style runtime"
 * for CosyVoice2). What differs is the session it hands back: a [SpeechTokenSession] whose
 * [SpeechTokenSession.generate] returns token ids, not the tensor-in/tensor-out
 * [InferenceSession] the ONNX seam returns. Modelling this AR decode as a named-tensor
 * [InferenceSession] — as the first CosyVoice2 skeleton did — was the bug this seam removes.
 */
interface SpeechTokenRuntime : Runtime {
    /** Open a speech-token decoder session over the model (e.g. a GGUF checkpoint) at [modelPath]. */
    fun openSpeechSession(
        modelPath: String,
        options: RuntimeOptions = RuntimeOptions(),
    ): SpeechTokenSession

    /**
     * A [SpeechTokenRuntime] does not produce tensor [InferenceSession]s — its output is token ids
     * via [openSpeechSession]. This inherited factory therefore fails loudly rather than pretend to
     * be an ONNX-style backend (the confusion this seam exists to remove). Callers holding only a
     * [Runtime] reference should feature-detect with `is SpeechTokenRuntime` before use.
     */
    override fun createSession(
        modelPath: String,
        options: RuntimeOptions,
    ): InferenceSession = error("$id is a SpeechTokenRuntime — call openSpeechSession() for speech-token decoding")
}

/**
 * One loaded speech-token decoder, ready to run. Unlike [InferenceSession], a single [generate]
 * call performs the *entire* autoregressive decode for one input (the KV-cache loop, sampler and
 * stop condition all live inside the native backend), returning the finished speech token ids.
 * Blocking by design — the engine already runs synthesis off the main thread (AbstractVoiceEngine
 * flows on Dispatchers.Default), matching how [InferenceSession.run] blocks.
 */
interface SpeechTokenSession : AutoCloseable {
    /** Decode [request] to a full sequence of speech token ids (25 Hz for CosyVoice2). */
    fun generate(request: SpeechTokenRequest): LongArray

    override fun close()
}

/**
 * Everything the speech-token decoder needs for one utterance. [textTokenIds] come from the
 * engine's frontend (Qwen2 BPE ids); [speakerEmbedding] and [promptSpeechTokens] come from the
 * bundled pre-baked voice (no reference wav in v1); [speed] is the model's native token-rate knob
 * — routed here, never used to resample output audio (CLAUDE.md rule 2).
 *
 * A plain class (not a `data class`): it holds arrays whose structural equality would be
 * misleading, and nothing in the seam compares requests.
 */
class SpeechTokenRequest(
    val textTokenIds: LongArray,
    val speakerEmbedding: FloatArray,
    val speed: Float,
    val promptSpeechTokens: LongArray = LongArray(0),
)
