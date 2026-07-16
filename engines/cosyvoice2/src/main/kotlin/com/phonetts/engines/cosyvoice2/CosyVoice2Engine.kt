package com.phonetts.engines.cosyvoice2

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Runtime
import com.phonetts.core.runtime.SpeechTokenRequest
import com.phonetts.core.runtime.SpeechTokenRuntime
import com.phonetts.core.runtime.SpeechTokenSession
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.closeAllQuietly
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.sideFileContainsAnyMarker
import java.io.File

/**
 * CosyVoice2-0.5B — the hardest model in the registry (see CLAUDE.md build order). It exists to
 * prove the seams are right, and its REAL pipeline is three genuinely different stages:
 *
 *  1. text -> Qwen2 BPE token ids ([CosyVoice2Frontend]);
 *  2. token ids -> speech token ids via an autoregressive LLM — the non-ONNX
 *     [SpeechTokenRuntime] (a llama.cpp / ggml GGUF loop on-device, id [LLM_RUNTIME_ID]). This is
 *     the "pluggable second runtime" the spec priced in, distinct from every other engine's ONNX;
 *  3. speech tokens -> mel -> waveform via the deterministic ONNX flow + HiFT graphs
 *     ([CosyVoice2Graphs], id [ONNX_RUNTIME_ID]).
 *
 * The speaker is a bundled pre-baked voice ([SpeakerPrompt]) — no reference wav in v1
 * (docs/research/cosyvoice2-mobile.md §Q5). Speed routes to the LLM's native token-rate parameter
 * ([SpeechTokenRequest.speed]) and is NEVER used to resample output audio (CLAUDE.md rule 2).
 *
 * Verified facts (docs/research/model-facts.md): 24000 Hz, Apache-2.0, native speed control.
 * The three-stage pipeline still fits the one `Flow<FloatArray>` generation path: each sentence
 * is one generate -> flow -> vocode pass, emitted as one chunk (spec §8).
 */
internal class CosyVoice2Engine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable without a real voices.bin on disk (parity with
    // KokoroEngine's fileReader seam) -- the baked voice is a binary little-endian blob.
    private val fileReader: (path: String) -> ByteArray = { File(it).readBytes() },
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private val frontend = CosyVoice2Frontend(context.phonemizer)
    private var state: LoadedState? = null

    override fun isLoaded(): Boolean = state != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        if (!looksLikeCosyVoice2(bundle)) return null
        return EngineMatch(id, buildDescriptor(bundle, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        require(hasAnyComponent(bundle)) {
            "bundle '${bundle.id}' has none of CosyVoice2's components (a .gguf LLM, $FLOW_FILE, $HIFT_FILE)" +
                " — cannot force-assign it to the CosyVoice2 engine"
        }
        return EngineMatch(id, buildDescriptor(bundle, Origin.SIDELOADED))
    }

    override suspend fun load(descriptor: ModelDescriptor) {
        val llmRuntime = requireSpeechTokenRuntime()
        val onnxRuntime = requireRuntime(context, ONNX_RUNTIME_ID, engineLabel)
        check(llmRuntime.isAvailable()) {
            "$engineLabel needs the native CosyVoice2 LLM backend (build the app with -PwithCosyVoice=true);" +
                " it is not available on this device, so CosyVoice2 cannot load"
        }
        val llmPath = requireAssetPath(descriptor, LLM_ASSET, engineLabel)
        val flowPath = requireAssetPath(descriptor, FLOW_ASSET, engineLabel)
        val hiftPath = requireAssetPath(descriptor, HIFT_ASSET, engineLabel)
        val prompt = loadSpeakerPrompt(descriptor)

        unload()
        state = openState(descriptor, llmRuntime, onnxRuntime, llmPath, flowPath, hiftPath, prompt)
    }

    // Rolls the partial load back on ANY failure so a half-open pipeline never leaks native
    // sessions on a 4 GB phone -- the same rationale as engines.common.openWithRollback, but here
    // over a mix of a SpeechTokenSession and two InferenceSessions.
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private fun openState(
        descriptor: ModelDescriptor,
        llmRuntime: SpeechTokenRuntime,
        onnxRuntime: Runtime,
        llmPath: String,
        flowPath: String,
        hiftPath: String,
        prompt: SpeakerPrompt,
    ): LoadedState {
        var llm: SpeechTokenSession? = null
        var flow: InferenceSession? = null
        try {
            llm = llmRuntime.openSpeechSession(llmPath)
            flow = onnxRuntime.createSession(flowPath)
            val hift = onnxRuntime.createSession(hiftPath)
            return LoadedState(descriptor, llm, flow, hift, prompt)
        } catch (failure: Throwable) {
            closeAllQuietly(flow)
            runCatching { llm?.close() }
            throw failure
        }
    }

    override fun unload() {
        state?.let {
            closeAllQuietly(it.flowSession, it.hiftSession)
            runCatching { it.llmSession.close() }
        }
        state = null
    }

    override fun voices(): List<Voice> = state?.descriptor?.voices ?: listOf(DEFAULT_VOICE)

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        speed: Float,
    ): FloatArray {
        val loaded = checkNotNull(state) { "$engineLabel.synthesizeSentence called before load()" }
        val voice = loaded.descriptor.voices.firstOrNull { it.id == voiceId } ?: loaded.descriptor.voices.first()
        val input = frontend.toModelInput(sentence, voice.language)
        val speechTokens =
            loaded.llmSession.generate(
                SpeechTokenRequest(
                    textTokenIds = input.tokenIds,
                    speakerEmbedding = loaded.prompt.embedding,
                    speed = speed,
                    promptSpeechTokens = loaded.prompt.promptTokens,
                ),
            )
        val mel = CosyVoice2Graphs.decodeFlow(loaded.flowSession, speechTokens, loaded.prompt, engineLabel)
        return CosyVoice2Graphs.vocode(loaded.hiftSession, mel, engineLabel)
    }

    private fun requireSpeechTokenRuntime(): SpeechTokenRuntime {
        val runtime = requireRuntime(context, LLM_RUNTIME_ID, engineLabel)
        return runtime as? SpeechTokenRuntime
            ?: error("$engineLabel: runtime '$LLM_RUNTIME_ID' is registered but is not a SpeechTokenRuntime")
    }

    private fun loadSpeakerPrompt(descriptor: ModelDescriptor): SpeakerPrompt {
        val path = descriptor.assetPaths[VOICES_ASSET] ?: return CosyVoice2SpeakerPrompt.fallback()
        val bytes = runCatching { fileReader(path) }.getOrNull() ?: return CosyVoice2SpeakerPrompt.fallback()
        return CosyVoice2SpeakerPrompt.parse(bytes) ?: CosyVoice2SpeakerPrompt.fallback()
    }

    /**
     * Fail-closed recognition (spec §9.1): confident only when ALL three pipeline components are
     * present (a `.gguf` LLM, the flow ONNX and the HiFT ONNX) AND the config side file carries a
     * CosyVoice2 signature. Anything less returns false so [inspect] refuses to guess.
     */
    private fun looksLikeCosyVoice2(bundle: ModelBundle): Boolean {
        if (llmFileIn(bundle) == null) return false
        if (!bundle.hasFile(FLOW_FILE) || !bundle.hasFile(HIFT_FILE)) return false
        return bundle.sideFileContainsAnyMarker(CONFIG_FILE, SIGNATURE_MARKERS)
    }

    private fun hasAnyComponent(bundle: ModelBundle): Boolean =
        llmFileIn(bundle) != null || bundle.hasFile(FLOW_FILE) || bundle.hasFile(HIFT_FILE)

    private fun llmFileIn(bundle: ModelBundle): String? = bundle.fileNames.firstOrNull { it.endsWith(LLM_SUFFIX) }

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
        val paths = mutableMapOf<String, String>()
        llmFileIn(bundle)?.let { paths[LLM_ASSET] = joinAssetPath(bundle, it) }
        putIfPresent(paths, bundle, FLOW_FILE, FLOW_ASSET)
        putIfPresent(paths, bundle, HIFT_FILE, HIFT_ASSET)
        putIfPresent(paths, bundle, VOICES_FILE, VOICES_ASSET)
        putIfPresent(paths, bundle, CONFIG_FILE, CONFIG_ASSET)
        return paths
    }

    private fun putIfPresent(
        paths: MutableMap<String, String>,
        bundle: ModelBundle,
        fileName: String,
        assetKey: String,
    ) {
        if (bundle.hasFile(fileName)) paths[assetKey] = joinAssetPath(bundle, fileName)
    }

    /** Everything the loaded engine needs to synthesize; null while unloaded (spec §5.5). */
    private class LoadedState(
        val descriptor: ModelDescriptor,
        val llmSession: SpeechTokenSession,
        val flowSession: InferenceSession,
        val hiftSession: InferenceSession,
        val prompt: SpeakerPrompt,
    )

    companion object {
        const val ENGINE_ID = "cosyvoice2"
        private const val DISPLAY_NAME = "CosyVoice2-0.5B"

        /** The non-ONNX, LLM-style backend this engine looks up in [EngineContext.runtimes]. */
        const val LLM_RUNTIME_ID = "cosyvoice-llm"

        /** The shared ONNX backend used for the deterministic flow + HiFT stages. */
        const val ONNX_RUNTIME_ID = "onnx"

        private const val SAMPLE_RATE_HZ = 24_000
        private val SPEED_RANGE = 0.5f..1.5f
        private const val DEFAULT_SPEED = 1.0f
        private const val DEFAULT_LANGUAGE = "en"
        private val DEFAULT_VOICE = Voice(id = "default", name = "Default Voice", language = DEFAULT_LANGUAGE)

        // Bundle layout this engine recognizes: the GGUF LLM (matched by suffix, since quant/name
        // varies), the flow + HiFT ONNX graphs, the baked voice bank, and the signed config.
        const val LLM_SUFFIX = ".gguf"
        const val FLOW_FILE = "flow.onnx"
        const val HIFT_FILE = "hift.onnx"
        const val VOICES_FILE = "voices.bin"
        const val CONFIG_FILE = "cosyvoice2.yaml"
        private val SIGNATURE_MARKERS = listOf("cosyvoice2", "qwen2lm")

        const val LLM_ASSET = "llm"
        const val FLOW_ASSET = "flow"
        const val HIFT_ASSET = "hift"
        const val VOICES_ASSET = "voices"
        const val CONFIG_ASSET = "config"
    }
}
