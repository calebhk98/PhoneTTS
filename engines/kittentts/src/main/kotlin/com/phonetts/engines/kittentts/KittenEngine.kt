package com.phonetts.engines.kittentts

import com.phonetts.core.engine.BlendableVoices
import com.phonetts.core.engine.BlendedVoiceSpec
import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.engine.VoiceBlend
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.model.Origin
import com.phonetts.core.model.ResourceCost
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.closeAllQuietly
import com.phonetts.engines.common.floatsOrError
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.sideFileContainsMarker
import java.io.File

/**
 * KittenTTS (KittenML/kitten-tts-nano-0.1) engine. VALIDATED (docs/research/onnx-io.md): the
 * real model is StyleTTS2, shape-identical to Kokoro — its ONNX graph takes `input_ids`,
 * a 256-dim `style` voice embedding, and `speed`, **not** an integer `speaker_id`. There is no
 * per-voice weight file; the 8 built-in voices are 256-dim style embedding rows in `voices.npz`,
 * selected by name at synthesis time exactly like [com.phonetts.engines.kokoro.KokoroEngine].
 *
 * Companion-file fingerprint used by [inspect] (spec §6.2 / §9.1 -- fail closed):
 *  - a `.onnx` weights file,
 *  - a [CONFIG_FILE] side file whose contents mention [CONFIG_MARKER],
 *  - a [VOICES_FILE] (`voices.npz`) file present by name. [ModelBundle] only carries small
 *    *text* side files for fingerprinting (`DirectoryBundleReader` explicitly excludes `.npz` as
 *    a weight-shaped binary format), so `inspect()`/`forcedMatch()` can only confirm this file
 *    exists -- they cannot read its embeddings. That is enough to fail closed on a bare `.onnx`
 *    or a foreign bundle; the real per-voice rows are decoded later by [KittenVoiceTable] once
 *    [load] has actual bytes to read.
 */
internal class KittenEngine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable without real files on disk (parity with
    // KokoroEngine's fileReader seam) -- here reading bytes rather than text, since voices.npz
    // is binary.
    private val fileReader: (path: String) -> ByteArray = { File(it).readBytes() },
) : AbstractVoiceEngine(context),
    BlendableVoices {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private val frontend = KittenFrontend(context.phonemizer)
    private var session: InferenceSession? = null
    private var voiceEmbeddings: Map<String, FloatArray> = emptyMap()
    private var loadedVoices: List<Voice> = emptyList()

    override fun isLoaded(): Boolean = session != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val modelFile = bundle.fileNames.firstOrNull { it.endsWith(MODEL_SUFFIX) } ?: return null
        if (!bundle.sideFileContainsMarker(CONFIG_FILE, CONFIG_MARKER)) return null
        if (!bundle.hasFile(VOICES_FILE)) return null

        return EngineMatch(id, buildDescriptor(bundle, modelFile, VOICE_NAMES, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val modelFile = bundle.fileNames.firstOrNull { it.endsWith(MODEL_SUFFIX) }
        // A bundle with no weights is a bad ARGUMENT (unusable input), not bad object state --
        // hence require/IllegalArgumentException, consistent with the other engines' forcedMatch.
        requireNotNull(modelFile) { "bundle '${bundle.id}' has no $MODEL_SUFFIX weights file; KittenTTS cannot run it" }

        // User-assigned bundle: honor the real 8-voice table by name when voices.npz is present,
        // otherwise supply the family default of a single generic voice so the dropdowns have
        // something to render (spec §6.2 -- "the chosen engine supplies its family defaults").
        val voiceNames = if (bundle.hasFile(VOICES_FILE)) VOICE_NAMES else DEFAULT_FAMILY_VOICES

        return EngineMatch(id, buildDescriptor(bundle, modelFile, voiceNames, Origin.SIDELOADED))
    }

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        val modelPath = requireAssetPath(descriptor, MODEL_ASSET_KEY, engineLabel)

        session?.close()
        session = runtime.createSession(modelPath)
        loadVoiceTable(descriptor)
    }

    override fun unload() {
        closeAllQuietly(session)
        session = null
        voiceEmbeddings = emptyMap()
        loadedVoices = emptyList()
    }

    override fun voices(): List<Voice> = loadedVoices

    /**
     * Voice mixing (issue #42): KittenTTS's StyleTTS2 graph feeds a continuous 256-dim `style`
     * vector, so an in-between voice is just the [VoiceBlend] of two loaded embeddings — registered
     * as a new selectable voice that synthesis then feeds like any other. Fails closed if either
     * source id isn't loaded.
     */
    override fun addBlendedVoice(spec: BlendedVoiceSpec): Voice? {
        val a = voiceEmbeddings[spec.voiceAId] ?: return null
        val b = voiceEmbeddings[spec.voiceBId] ?: return null
        val voice = Voice(id = spec.id, name = spec.name, language = LANGUAGE)
        voiceEmbeddings = voiceEmbeddings + (spec.id to VoiceBlend.blend(a, b, spec.weight))
        loadedVoices = loadedVoices.filterNot { it.id == spec.id } + voice
        return voice
    }

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val speed = params.speed
        val activeSession = session ?: error("$engineLabel.synthesizeSentence called before load()")
        val embedding = voiceEmbeddings[voiceId] ?: error("Unknown KittenTTS voice id '$voiceId'")

        val modelInput = frontend.toModelInput(sentence, LANGUAGE)
        // VALIDATED against KittenML/kitten-tts-nano-0.1 (inspected 2026-07, docs/research/onnx-io.md):
        // the graph's inputs are "input_ids" int64 [1, T], "style" float32 [1, 256] (the selected
        // voice's embedding row), "speed" float32 [1]; the audio output is "waveform" float32 [samples]
        // (a second "duration" output exists but is ignored).
        val inputs =
            mapOf(
                INPUT_IDS_KEY to Tensor.longs(modelInput.tokenIds, intArrayOf(1, modelInput.tokenIds.size)),
                STYLE_KEY to Tensor.floats(embedding, intArrayOf(1, embedding.size)),
                SPEED_KEY to Tensor.scalarFloat(speed),
            )
        val outputs = activeSession.run(inputs)
        return outputs.floatsOrError(WAVEFORM_KEY, engineLabel)
    }

    private fun loadVoiceTable(descriptor: ModelDescriptor) {
        val tablePath = descriptor.assetPaths[VOICES_ASSET_KEY]
        val entries = tablePath?.let { KittenVoiceTable.parse(fileReader(it)) }.orEmpty()

        if (entries.isEmpty()) {
            // Sideloaded/forced-match bundle with no real voices.npz on disk: fall back to a
            // single zero-vector "default" style so synthesize() still has something to feed the
            // graph. Real voice quality for such bundles is out of scope for this seam (spec TDD
            // note: "test the plumbing, not the audio").
            voiceEmbeddings = mapOf(descriptor.defaultVoiceId to FloatArray(FALLBACK_EMBEDDING_SIZE))
            loadedVoices = descriptor.voices
            return
        }

        voiceEmbeddings = entries.associate { it.voice.id to it.embedding }
        loadedVoices = entries.map { it.voice }
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        modelFile: String,
        voiceNames: List<String>,
        origin: Origin,
    ): ModelDescriptor {
        val voices = voiceNames.map { name -> Voice(id = name, name = name, language = LANGUAGE) }
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = origin,
            // 24000 Hz is fixed across all KittenTTS variants (docs/research/model-facts.md,
            // verified) -- this is the descriptor layer, the one sanctioned place for it (spec
            // §5.7); nothing outside this engine may repeat this literal.
            sampleRate = SAMPLE_RATE,
            voices = voices,
            defaultVoiceId = voices.first().id,
            // Introspected: KittenTTS's StyleTTS2 graph has a native "speed" input, so it advertises
            // a speed knob (routed to that scalar — never resampled, CLAUDE.md rule 2).
            parameters = listOf(ModelParameter.speed(SPEED_RANGE, DEFAULT_SPEED)),
            // Introspected: the StyleTTS2 graph takes a continuous 256-dim `style` vector, so two
            // voices can be linearly interpolated into an in-between one (issue #42). A descriptor
            // fact the "mix voices" UI derives from — never a model-name special case (rule 5).
            supportsVoiceBlend = true,
            assetPaths =
                mapOf(
                    MODEL_ASSET_KEY to joinAssetPath(bundle, modelFile),
                    CONFIG_ASSET_KEY to joinAssetPath(bundle, CONFIG_FILE),
                    VOICES_ASSET_KEY to joinAssetPath(bundle, VOICES_FILE),
                ),
            // Approximate peak-RAM estimate (issue #38): KittenTTS is the tiny ~15M-param model, the
            // lightest engine here. A-priori only — refined by observed peak RAM.
            resourceCost = ResourceCost.peakRamMebibytes(PEAK_RAM_MIB),
        )
    }

    companion object {
        const val ENGINE_ID = "kittentts"
        const val DISPLAY_NAME = "KittenTTS"

        /** id of the [com.phonetts.core.runtime.Runtime] this engine runs on (spec: ONNX for KittenTTS). */
        const val RUNTIME_ID = "onnx"

        const val SAMPLE_RATE = 24_000

        // Approximate peak resident RAM (MiB) while loaded + generating — the lightest model here.
        private const val PEAK_RAM_MIB = 80L

        // VALIDATED (docs/research/onnx-io.md): the ONNX graph takes a native "speed" scalar, same
        // family/range convention as the sibling ONNX engines.
        val SPEED_RANGE = 0.5f..1.5f
        const val DEFAULT_SPEED = 1.0f

        // English-only dev-preview as of v0.8 (docs/research/model-facts.md) -- ASSUMPTION that
        // may need to become descriptor-driven if upstream adds languages.
        const val LANGUAGE = "en"

        const val MODEL_SUFFIX = ".onnx"
        const val CONFIG_FILE = "config.json"

        // VALIDATED (docs/research/onnx-io.md): the real voice table is a `voices.npz` binary
        // archive, not a `voices.json` name array.
        const val VOICES_FILE = "voices.npz"
        private const val CONFIG_MARKER = "kitten_tts"

        const val MODEL_ASSET_KEY = "model"
        const val CONFIG_ASSET_KEY = "config"
        const val VOICES_ASSET_KEY = "voices"

        // VALIDATED (docs/research/onnx-io.md) ONNX graph tensor names (KittenML/kitten-tts-nano-0.1).
        const val INPUT_IDS_KEY = "input_ids"
        const val STYLE_KEY = "style"
        const val SPEED_KEY = "speed"
        const val WAVEFORM_KEY = "waveform"

        private const val FALLBACK_EMBEDDING_SIZE = 1

        // VALIDATED (docs/research/onnx-io.md): the 8 named style embeddings voices.npz ships,
        // one row per `.npy` entry (name minus the `.npy` suffix).
        val VOICE_NAMES =
            listOf(
                "expr-voice-2-m",
                "expr-voice-2-f",
                "expr-voice-3-m",
                "expr-voice-3-f",
                "expr-voice-4-m",
                "expr-voice-4-f",
                "expr-voice-5-m",
                "expr-voice-5-f",
            )

        /** Family default used by [forcedMatch] when no real voices.npz is present. */
        private val DEFAULT_FAMILY_VOICES = listOf("Default")
    }
}
