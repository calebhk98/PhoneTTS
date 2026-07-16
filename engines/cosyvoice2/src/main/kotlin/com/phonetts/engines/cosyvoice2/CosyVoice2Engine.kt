package com.phonetts.engines.cosyvoice2

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.runtime.NativeTtsRuntime
import com.phonetts.core.runtime.NativeTtsSession
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime

/**
 * CosyVoice — the hardest model in the registry (see CLAUDE.md build order). It exists to prove the
 * seams are right, and it is the one engine whose runtime is NOT ONNX.
 *
 * The deployable on-device model is **CosyVoice3-0.5B** (Apache-2.0, GGUF-native — the sibling of
 * the CosyVoice2 model proven in PyTorch), and its runtime is CrispStrobe/CrispASR's self-contained
 * C++/ggml `cosyvoice3_tts` engine: a Qwen2-0.5B LLM (speech-token head) → DiT-CFM flow →
 * HiFi-GAN/iSTFT HiFT, with a native Qwen2 BPE tokenizer and a baked voice bank, all in one library
 * (docs/COSYVOICE2.md; proven end-to-end in `scripts/model-verify/run_cosy_native.sh`).
 *
 * Because that native library does the *entire* text→audio pipeline in one call, this engine is a
 * thin delegate over the [NativeTtsRuntime] seam (id [NATIVE_RUNTIME_ID]) — there is deliberately no
 * Kotlin [com.phonetts.core.engine.TextFrontend], speech-token stage, or ONNX flow/HiFT graph here
 * (the earlier skeleton's Qwen2 token-id placeholder frontend and separate ONNX graphs are gone).
 * The voice list is the SSOT-clean set the native session reads from the model's voices GGUF.
 *
 * Parameters: the CrispASR synth C ABI exposes no speed (or any other) knob, and CLAUDE.md rule 2
 * forbids resampling output audio to fake one (that shifts pitch). So this engine declares **no
 * tunable parameters** — the descriptor's parameter list is empty, the UI shows no speed control,
 * and [ModelDescriptor.speedRange] resolves to a locked `1.0..1.0`. Honest-closed: a speed knob will
 * appear only if/when the native path genuinely routes one.
 *
 * One engine loaded at a time (spec §5.5): [load] opens one [NativeTtsSession], [unload] closes it.
 */
internal class CosyVoice2Engine(
    context: EngineContext,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private var state: LoadedState? = null

    override fun isLoaded(): Boolean = state != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        if (!looksLikeCosyVoice(bundle)) return null
        return EngineMatch(id, buildDescriptor(bundle, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        require(hasAnyComponent(bundle)) {
            "bundle '${bundle.id}' has none of CosyVoice's GGUF components (an LLM/flow/HiFT/voices" +
                " '$GGUF_SUFFIX') — cannot force-assign it to the CosyVoice engine"
        }
        return EngineMatch(id, buildDescriptor(bundle, Origin.SIDELOADED))
    }

    override suspend fun load(descriptor: ModelDescriptor) {
        val runtime = requireNativeTtsRuntime()
        check(runtime.isAvailable()) {
            "$engineLabel needs the native CosyVoice ggml backend (build the app with -PwithCosyVoice=true);" +
                " it is not available on this device, so CosyVoice cannot load"
        }
        // The native runtime loads all four GGUF stages that sit as siblings in the model directory,
        // so hand it that directory (the parent of the LLM gguf), not a single file.
        val llmPath = requireAssetPath(descriptor, LLM_ASSET, engineLabel)
        val modelDir = llmPath.substringBeforeLast('/', missingDelimiterValue = ".")

        unload()
        val session = runtime.openTtsSession(modelDir)
        state = LoadedState(descriptor, session, voicesFrom(session))
    }

    override fun unload() {
        state?.let { runCatching { it.session.close() } }
        state = null
    }

    // After load(), the voice list is the SSOT set the native session read from the voices GGUF;
    // before load() (the picker showing a not-yet-loaded model), fall back to the descriptor's
    // single default so the UI always has something valid to show.
    override fun voices(): List<Voice> = state?.voices ?: state?.descriptor?.voices ?: listOf(DEFAULT_VOICE)

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val loaded = checkNotNull(state) { "$engineLabel.synthesizeSentence called before load()" }
        // params is intentionally unused: this engine declares NO tunable parameters (the native
        // synth has no speed/other knob — see buildDescriptor), so there is nothing to route and
        // audio is never resampled (CLAUDE.md rule 2).
        val voiceName = loaded.voices.firstOrNull { it.id == voiceId }?.id ?: loaded.voices.first().id
        return loaded.session.synthesize(NativeTtsRequest(text = sentence, voiceName = voiceName))
    }

    private fun requireNativeTtsRuntime(): NativeTtsRuntime {
        val runtime = requireRuntime(context, NATIVE_RUNTIME_ID, engineLabel)
        return runtime as? NativeTtsRuntime
            ?: error("$engineLabel: runtime '$NATIVE_RUNTIME_ID' is registered but is not a NativeTtsRuntime")
    }

    private fun voicesFrom(session: NativeTtsSession): List<Voice> {
        val names = session.voiceNames
        if (names.isEmpty()) return listOf(DEFAULT_VOICE)
        return names.map { name -> Voice(id = name, name = prettyVoiceName(name), language = languageOf(name)) }
    }

    /**
     * Fail-closed recognition (spec §9.1): confident only when ALL FOUR GGUF stages of the native
     * CosyVoice3 pipeline are present — an LLM, flow, HiFT and voices GGUF, matched by their
     * conventional `cosyvoice3-<stage>-*.gguf` names. That four-file set is itself the signature;
     * anything less returns false so [inspect] refuses to guess.
     */
    private fun looksLikeCosyVoice(bundle: ModelBundle): Boolean =
        STAGE_PREFIXES.all { prefix -> stageFile(bundle, prefix) != null }

    private fun hasAnyComponent(bundle: ModelBundle): Boolean =
        STAGE_PREFIXES.any { prefix -> stageFile(bundle, prefix) != null }

    /** The bundle file for a stage (e.g. `cosyvoice3-llm-q4_k.gguf` for the `cosyvoice3-llm` prefix). */
    private fun stageFile(
        bundle: ModelBundle,
        prefix: String,
    ): String? = bundle.fileNames.firstOrNull { it.startsWith(prefix) && it.endsWith(GGUF_SUFFIX) }

    private fun buildDescriptor(
        bundle: ModelBundle,
        origin: Origin,
    ): ModelDescriptor =
        ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = origin,
            sampleRate = SAMPLE_RATE_HZ,
            voices = listOf(DEFAULT_VOICE),
            defaultVoiceId = DEFAULT_VOICE.id,
            // No tunable parameters: introspected fact — the CrispASR synth C ABI exposes no speed
            // (or any other) knob, so the descriptor advertises none and the UI shows no speed
            // control (rather than a fake one that would need resampling, CLAUDE.md rule 2).
            parameters = emptyList(),
            assetPaths = buildAssetPaths(bundle),
        )

    // Only the LLM path is needed at load() (its parent dir is the model dir the native runtime
    // auto-discovers all four stages from). The others are recorded for completeness / diagnostics.
    private fun buildAssetPaths(bundle: ModelBundle): Map<String, String> {
        val paths = mutableMapOf<String, String>()
        STAGE_PREFIXES.forEach { prefix ->
            stageFile(bundle, prefix)?.let { paths[prefix] = joinAssetPath(bundle, it) }
        }
        return paths
    }

    /** Everything the loaded engine needs to synthesize; null while unloaded (spec §5.5). */
    private class LoadedState(
        val descriptor: ModelDescriptor,
        val session: NativeTtsSession,
        val voices: List<Voice>,
    )

    companion object {
        const val ENGINE_ID = "cosyvoice2"
        private const val DISPLAY_NAME = "CosyVoice3-0.5B"

        /** The non-ONNX, full-pipeline backend this engine looks up in [EngineContext.runtimes]. */
        const val NATIVE_RUNTIME_ID = "cosyvoice"

        private const val SAMPLE_RATE_HZ = 24_000

        // CosyVoice3 is multilingual and reads any language directly, so this default is cosmetic
        // (the native BPE tokenizer, not the Kotlin phonemizer, handles text). zero_shot is the
        // always-present Mandarin baked voice in every CosyVoice3 voices GGUF.
        private const val DEFAULT_LANGUAGE = "zh"
        private val DEFAULT_VOICE = Voice(id = "zero_shot", name = "Zero-shot", language = DEFAULT_LANGUAGE)

        // Bundle layout this engine recognizes: the four GGUF stages of the cstr/cosyvoice3 stack,
        // matched by stage prefix (quant suffix varies: q4_k / q8_0 / f16). These prefixes double as
        // the assetPaths keys.
        const val GGUF_SUFFIX = ".gguf"
        const val LLM_ASSET = "cosyvoice3-llm"
        const val FLOW_ASSET = "cosyvoice3-flow"
        const val HIFT_ASSET = "cosyvoice3-hift"
        const val VOICES_ASSET = "cosyvoice3-voices"
        private val STAGE_PREFIXES = listOf(LLM_ASSET, FLOW_ASSET, HIFT_ASSET, VOICES_ASSET)

        // "fleurs-en" -> "en"; "zero_shot" -> the Mandarin default. Name-derived only (no fabricated
        // table): CosyVoice3's per-voice language is cosmetic since the model reads any language.
        private fun languageOf(voiceName: String): String =
            voiceName.substringAfter("fleurs-", missingDelimiterValue = DEFAULT_LANGUAGE)

        // "fleurs-en" -> "Fleurs En"; "zero_shot" -> "Zero Shot".
        private fun prettyVoiceName(voiceName: String): String =
            voiceName.split('-', '_').joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
}
