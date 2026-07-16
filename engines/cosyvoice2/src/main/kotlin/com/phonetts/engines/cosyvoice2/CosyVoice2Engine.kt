package com.phonetts.engines.cosyvoice2

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.Voice
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.text.TextChunker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * CosyVoice2-0.5B — the hardest model in the registry (see CLAUDE.md build order). It exists
 * to prove two things about the seams: (1) [com.phonetts.core.runtime.Runtime] is genuinely
 * pluggable — this engine asks [EngineContext.runtimes] for an LLM-style backend by id, never
 * the ONNX one every other engine uses, and (2) an autoregressive, multi-component pipeline
 * (Qwen2LM backbone -> flow-matching decoder -> HiFiGAN vocoder, "hift" in upstream CosyVoice2)
 * still fits the single `Flow<FloatArray>` generation path: each AR step is one
 * [InferenceSession.run] call in a loop, and each finished sentence is one emission.
 *
 * Verified facts (docs/research/model-facts.md): sample rate 24000 Hz, Apache-2.0, native
 * speed control in the ~0.5-1.5 range (prompt-token interpolation/downsampling) — routed to
 * [LLM_INPUT_SPEED] below, never used to resample output audio (CLAUDE.md rule 2).
 *
 * Real tensor names are not yet known (the real runtime lands later); the ones used here are
 * documented assumptions, called out at each call site.
 */
internal class CosyVoice2Engine(
    private val context: EngineContext,
) : VoiceEngine {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME

    private val frontend = CosyVoice2Frontend(context.phonemizer)
    private var state: LoadedState? = null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        if (!looksLikeCosyVoice2(bundle)) return null
        return EngineMatch(id, buildDescriptor(bundle, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        require(REQUIRED_WEIGHT_FILES.any(bundle::hasFile)) {
            "bundle '${bundle.id}' has none of CosyVoice2's weight files ($REQUIRED_WEIGHT_FILES)" +
                " — cannot force-assign it to the CosyVoice2 engine"
        }
        return EngineMatch(id, buildDescriptor(bundle, Origin.SIDELOADED))
    }

    override suspend fun load(descriptor: ModelDescriptor) {
        val runtime =
            context.runtimes.get(RUNTIME_ID)
                ?: error(
                    "CosyVoice2Engine requires a runtime registered under id '$RUNTIME_ID' in " +
                        "EngineContext.runtimes; none is registered, cannot load '${descriptor.modelId}'",
                )
        val llmPath = requireAssetPath(descriptor, LLM_ASSET_KEY)
        val flowPath = requireAssetPath(descriptor, FLOW_ASSET_KEY)
        val hiftPath = requireAssetPath(descriptor, HIFT_ASSET_KEY)

        unload()
        state =
            LoadedState(
                descriptor = descriptor,
                llmSession = runtime.createSession(llmPath),
                flowSession = runtime.createSession(flowPath),
                hiftSession = runtime.createSession(hiftPath),
            )
    }

    override fun unload() {
        state?.let {
            it.llmSession.close()
            it.flowSession.close()
            it.hiftSession.close()
        }
        state = null
    }

    override fun voices(): List<Voice> = state?.descriptor?.voices ?: listOf(DEFAULT_VOICE)

    override fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
    ): Flow<FloatArray> {
        val loaded = state ?: error("CosyVoice2Engine.synthesize called before load()")
        val language = loaded.descriptor.voices.firstOrNull { it.id == voiceId }?.language ?: DEFAULT_LANGUAGE
        val sentences = TextChunker.intoSentences(text)
        return flow {
            for (sentence in sentences) {
                emit(synthesizeSentence(loaded, sentence, language, speed))
            }
        }
    }

    private fun synthesizeSentence(
        loaded: LoadedState,
        sentence: String,
        language: String,
        speed: Float,
    ): FloatArray {
        val input = frontend.toModelInput(sentence, language)
        val tokens = generateTokens(loaded.llmSession, input.tokenIds, speed)
        val mel = runFlowDecoder(loaded.flowSession, tokens)
        return runVocoder(loaded.hiftSession, mel)
    }

    /**
     * Autoregression: call the LLM backbone in a loop, one token per [InferenceSession.run]
     * call, feeding the growing sequence back in each step (assumed input [LLM_INPUT_TOKENS])
     * along with the native speed knob (assumed input [LLM_INPUT_SPEED], per model-facts
     * "dynamic speed control via prompt token interpolation" — sent every step since we don't
     * know yet whether the real graph is speed-conditioned once or per-step). Stops on the
     * model's own EOS signal (assumed output [LLM_OUTPUT_STOP]) or [MAX_AR_STEPS] as a safety
     * bound against a runaway loop if a real graph never signals stop.
     */
    private fun generateTokens(
        session: InferenceSession,
        promptTokens: LongArray,
        speed: Float,
    ): LongArray {
        val generated = mutableListOf<Long>()
        var sequence = promptTokens
        repeat(MAX_AR_STEPS) {
            val outputs =
                session.run(
                    mapOf(
                        LLM_INPUT_TOKENS to Tensor.longs(sequence),
                        LLM_INPUT_SPEED to Tensor.scalarFloat(speed),
                    ),
                )
            val nextToken = outputs.getValue(LLM_OUTPUT_NEXT_TOKEN).asLongs().first()
            generated.add(nextToken)
            sequence = sequence + nextToken
            val stop = outputs[LLM_OUTPUT_STOP]?.asFloats()?.firstOrNull() ?: 0f
            if (stop >= STOP_THRESHOLD) return generated.toLongArray()
        }
        return generated.toLongArray()
    }

    /** Flow-matching decoder: speech tokens -> mel/latent (assumed I/O names, single shot, non-AR). */
    private fun runFlowDecoder(
        session: InferenceSession,
        tokens: LongArray,
    ): Tensor {
        val outputs = session.run(mapOf(FLOW_INPUT_TOKENS to Tensor.longs(tokens)))
        return outputs[FLOW_OUTPUT_MEL]
            ?: error("CosyVoice2Engine: flow-matching decoder produced no '$FLOW_OUTPUT_MEL' output")
    }

    /** HiFiGAN vocoder ("hift" upstream): mel -> raw waveform @ 24000 Hz (assumed I/O names). */
    private fun runVocoder(
        session: InferenceSession,
        mel: Tensor,
    ): FloatArray {
        val outputs = session.run(mapOf(VOCODER_INPUT_MEL to mel))
        val audio =
            outputs[VOCODER_OUTPUT_AUDIO]
                ?: error("CosyVoice2Engine: vocoder produced no '$VOCODER_OUTPUT_AUDIO' output")
        return audio.asFloats()
    }

    private fun requireAssetPath(
        descriptor: ModelDescriptor,
        key: String,
    ): String =
        descriptor.assetPaths[key]
            ?: error("CosyVoice2Engine: descriptor '${descriptor.modelId}' is missing asset path '$key'")

    /**
     * Fail-closed recognition (spec §9.1): confident only when ALL three weight components are
     * present AND the config side file carries a CosyVoice2 signature. Any single component
     * missing, or an unrecognized config, returns false so [inspect] refuses to guess.
     */
    private fun looksLikeCosyVoice2(bundle: ModelBundle): Boolean {
        if (!REQUIRED_WEIGHT_FILES.all(bundle::hasFile)) return false
        val config = bundle.sideFile(CONFIG_FILE_NAME) ?: return false
        return SIGNATURE_MARKERS.any { marker -> config.contains(marker, ignoreCase = true) }
    }

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
            speedRange = SPEED_RANGE,
            defaultVoiceId = DEFAULT_VOICE.id,
            defaultSpeed = DEFAULT_SPEED,
            assetPaths = buildAssetPaths(bundle),
        )

    private fun buildAssetPaths(bundle: ModelBundle): Map<String, String> {
        val root = bundle.rootPath
        val paths = mutableMapOf<String, String>()
        for (fileName in REQUIRED_WEIGHT_FILES) {
            if (!bundle.hasFile(fileName)) continue
            paths[fileName.substringBefore(".")] = if (root.isNullOrEmpty()) fileName else "$root/$fileName"
        }
        if (bundle.hasFile(CONFIG_FILE_NAME)) {
            paths[CONFIG_ASSET_KEY] = if (root.isNullOrEmpty()) CONFIG_FILE_NAME else "$root/$CONFIG_FILE_NAME"
        }
        return paths
    }

    /** Everything the loaded engine needs to synthesize; null while unloaded (spec §5.5). */
    private class LoadedState(
        val descriptor: ModelDescriptor,
        val llmSession: InferenceSession,
        val flowSession: InferenceSession,
        val hiftSession: InferenceSession,
    )

    companion object {
        const val ENGINE_ID = "cosyvoice2"
        private const val DISPLAY_NAME = "CosyVoice2-0.5B"

        /** The id this engine looks up in [EngineContext.runtimes] — a second, LLM-style backend. */
        const val RUNTIME_ID = "cosyvoice-llm"

        private const val SAMPLE_RATE_HZ = 24_000
        private val SPEED_RANGE = 0.5f..1.5f
        private const val DEFAULT_SPEED = 1.0f
        private const val DEFAULT_LANGUAGE = "en"
        private val DEFAULT_VOICE = Voice(id = "default", name = "Default Voice", language = DEFAULT_LANGUAGE)

        // Bundle layout this engine recognizes (upstream CosyVoice2 file names, exported for the
        // on-device LLM-style runtime): the three weight components plus its config file.
        private val REQUIRED_WEIGHT_FILES = setOf("llm.onnx", "flow.onnx", "hift.onnx")
        private const val CONFIG_FILE_NAME = "cosyvoice2.yaml"
        private val SIGNATURE_MARKERS = listOf("cosyvoice2", "qwen2lm")

        const val LLM_ASSET_KEY = "llm"
        const val FLOW_ASSET_KEY = "flow"
        const val HIFT_ASSET_KEY = "hift"
        const val CONFIG_ASSET_KEY = "config"

        // Assumed real tensor I/O names — the native runtime lands later; these are documented
        // guesses that keep the seam exercised end-to-end (see class doc).
        const val LLM_INPUT_TOKENS = "input_ids"
        const val LLM_INPUT_SPEED = "speed"
        const val LLM_OUTPUT_NEXT_TOKEN = "next_token"
        const val LLM_OUTPUT_STOP = "stop"
        const val FLOW_INPUT_TOKENS = "speech_tokens"
        const val FLOW_OUTPUT_MEL = "mel"
        const val VOCODER_INPUT_MEL = "mel"
        const val VOCODER_OUTPUT_AUDIO = "audio"

        private const val STOP_THRESHOLD = 0.5f

        /** Safety bound on the AR loop — an implementation guard, not a model fact. */
        private const val MAX_AR_STEPS = 200
    }
}
