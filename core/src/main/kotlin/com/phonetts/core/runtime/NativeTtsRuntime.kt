package com.phonetts.core.runtime

/**
 * The second, non-ONNX inference backend the spec anticipated (§5.3) — the "pluggable LLM-style
 * runtime" for CosyVoice. Unlike the ONNX seam, which runs one named-tensor forward pass per call,
 * this backend owns an **entire text→audio pipeline** behind a single native call.
 *
 * That shape is not a shortcut — it is what the on-device CosyVoice implementation actually is. The
 * deployable model is **CosyVoice3-0.5B** (Apache-2.0, the GGUF-native sibling of the CosyVoice2
 * model proven in PyTorch), and its runtime is CrispStrobe/CrispASR's self-contained C++/ggml
 * `cosyvoice3_tts` engine: Qwen2-0.5B LLM (with a speech-token head) → DiT-CFM flow → HiFi-GAN/iSTFT
 * HiFT, plus a native Qwen2 BPE tokenizer and a baked voice bank — all in one library, proven
 * end-to-end in `scripts/model-verify/run_cosy_native.sh` (147 tokens → 5.88 s of real 24 kHz audio).
 *
 * Because the native lib tokenizes, decodes, flows, and vocodes internally, there is deliberately
 * **no** Kotlin `TextFrontend`, speech-token stage, or ONNX flow/HiFT graph for this engine — the
 * earlier skeleton's Qwen2 "token-id placeholder" frontend and separate ONNX flow/HiFT sessions are
 * removed. An engine hands this runtime raw text + a voice name and gets finished PCM back.
 *
 * It is still a [Runtime], so it registers in the same [com.phonetts.core.registry.RuntimeRegistry]
 * alongside the ONNX backend and an engine looks it up by id exactly the same way. What differs is
 * the session it returns: a [NativeTtsSession] that synthesizes audio, not the tensor-in/tensor-out
 * [InferenceSession] the ONNX seam returns.
 */
interface NativeTtsRuntime : Runtime {
    /**
     * Open a full TTS session over a model **directory** — the folder holding the four GGUF files
     * (LLM, flow, HiFT, voices). A directory rather than a single file because the native runtime
     * loads all four stages; the concrete backend discovers them by their conventional names.
     */
    fun openTtsSession(
        modelDir: String,
        options: RuntimeOptions = RuntimeOptions(),
    ): NativeTtsSession

    /**
     * A [NativeTtsRuntime] does not produce tensor [InferenceSession]s — its output is audio via
     * [openTtsSession]. This inherited factory therefore fails loudly rather than pretend to be an
     * ONNX-style backend. Callers holding only a [Runtime] reference should feature-detect with
     * `is NativeTtsRuntime` before use.
     */
    override fun createSession(
        modelPath: String,
        options: RuntimeOptions,
    ): InferenceSession = error("$id is a NativeTtsRuntime — call openTtsSession() for full TTS synthesis")
}

/**
 * One loaded native TTS pipeline, ready to synthesize. A single [synthesize] call performs the
 * *entire* text→audio decode for one utterance (BPE tokenization, the AR speech-token loop, the
 * flow-matching ODE, and the vocoder all live inside the native backend), returning finished PCM at
 * [sampleRate]. Blocking by design — the engine already runs synthesis off the main thread
 * (AbstractVoiceEngine flows on Dispatchers.Default), matching how [InferenceSession.run] blocks.
 */
interface NativeTtsSession : AutoCloseable {
    /** Output sample rate of the loaded model (24 kHz for CosyVoice3). */
    val sampleRate: Int

    /**
     * The names of the baked voices the loaded model carries (e.g. `zero_shot`, `fleurs-en`), read
     * from the model's voices GGUF. These are the SSOT for the engine's [Voice] list — the engine
     * never hardcodes a voice name.
     */
    val voiceNames: List<String>

    /** Synthesize [request] to finished PCM at [sampleRate]. */
    fun synthesize(request: NativeTtsRequest): FloatArray

    override fun close()
}

/**
 * Everything the native pipeline needs for one utterance: the [text] to speak and the [voiceName]
 * to clone (one of [NativeTtsSession.voiceNames]).
 *
 * There is intentionally **no speed field**: the CrispASR `cosyvoice3_tts_synth` C ABI does not
 * expose a speed knob, and CLAUDE.md rule 2 forbids resampling output audio to fake one (that would
 * shift pitch). CosyVoice3 therefore advertises a locked speed of 1.0 in its descriptor until the
 * native synth path routes a native token-rate parameter — honest-closed rather than wrong.
 *
 * A plain class (not a `data class`): nothing in the seam compares requests.
 */
class NativeTtsRequest(
    val text: String,
    val voiceName: String,
)
