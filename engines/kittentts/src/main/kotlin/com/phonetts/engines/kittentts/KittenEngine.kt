package com.phonetts.engines.kittentts

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
 * KittenTTS (KittenML/KittenTTS) engine: a single ONNX graph with ~8 built-in speakers, no
 * per-voice files (spec §5.7 table; docs/research/model-facts.md). Dev-preview, Apache-2.0,
 * English-only as of v0.8 — [LANGUAGE] is hardcoded to "en" for that reason and will need to
 * become descriptor-driven if/when upstream adds languages.
 *
 * Companion-file fingerprint used by [inspect] (spec §6.2 / §9.1 — fail closed):
 *  - a `.onnx` weights file,
 *  - a [CONFIG_FILE] side file whose contents mention [CONFIG_MARKER] (ASSUMPTION: real
 *    KittenTTS config.json carries a "model_type"/"architecture" field identifying the
 *    family; we key off a substring match rather than a full JSON parse, since this module
 *    pulls in no JSON library),
 *  - a [VOICES_FILE] side file: ASSUMED to be a flat JSON array of speaker-name strings, e.g.
 *    `["Bella","Jasper","Luna","Bruno","Rosie","Hugo","Kiki","Leo"]` (the 8 names KittenTTS
 *    ships per model-facts.md). A bare `.onnx` with none of these companions, or a bundle
 *    whose config doesn't carry the marker, is refused — never guessed.
 */
internal class KittenEngine(private val context: EngineContext) : VoiceEngine {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME

    private val frontend = KittenFrontend(context.phonemizer)
    private var session: InferenceSession? = null
    private var loadedDescriptor: ModelDescriptor? = null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val modelFile = bundle.fileNames.firstOrNull { it.endsWith(MODEL_SUFFIX) } ?: return null
        val config = bundle.sideFile(CONFIG_FILE)
        if (config == null || !config.contains(CONFIG_MARKER, ignoreCase = true)) return null

        val voicesRaw = bundle.sideFile(VOICES_FILE) ?: return null
        val speakerNames = parseSpeakerNames(voicesRaw)
        if (speakerNames.isEmpty()) return null

        return EngineMatch(id, buildDescriptor(bundle, modelFile, speakerNames, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val modelFile = bundle.fileNames.firstOrNull { it.endsWith(MODEL_SUFFIX) }
        // A bundle with no weights is a bad ARGUMENT (unusable input), not bad object state —
        // hence require/IllegalArgumentException, consistent with the other engines' forcedMatch.
        requireNotNull(modelFile) { "bundle '${bundle.id}' has no $MODEL_SUFFIX weights file; KittenTTS cannot run it" }

        // User-assigned bundle: honor a real speaker table if present, otherwise supply the
        // family default of a single generic voice so the dropdowns have something to render
        // (spec §6.2 — "the chosen engine supplies its family defaults").
        val speakerNames =
            bundle.sideFile(VOICES_FILE)?.let(::parseSpeakerNames)?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_FAMILY_VOICES

        return EngineMatch(id, buildDescriptor(bundle, modelFile, speakerNames, Origin.SIDELOADED))
    }

    override suspend fun load(descriptor: ModelDescriptor) {
        val runtime = context.runtimes.get(RUNTIME_ID) ?: error("runtime '$RUNTIME_ID' is not registered")
        val modelPath =
            descriptor.assetPaths[MODEL_ASSET_KEY]
                ?: error("descriptor for '${descriptor.modelId}' is missing its '$MODEL_ASSET_KEY' asset path")

        session?.close()
        session = runtime.createSession(modelPath)
        loadedDescriptor = descriptor
    }

    override fun unload() {
        session?.close()
        session = null
        loadedDescriptor = null
    }

    override fun voices(): List<Voice> = loadedDescriptor?.voices ?: emptyList()

    override fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
    ): Flow<FloatArray> {
        val activeSession = session ?: error("KittenEngine.synthesize called before load()")
        val descriptor = loadedDescriptor ?: error("KittenEngine.synthesize called before load()")
        val speakerIndex = resolveSpeakerIndex(descriptor, voiceId)

        return flow {
            TextChunker.intoSentences(text).forEach { sentence ->
                emit(synthesizeSentence(activeSession, sentence, speakerIndex, speed))
            }
        }
    }

    private fun synthesizeSentence(
        activeSession: InferenceSession,
        sentence: String,
        speakerIndex: Long,
        speed: Float,
    ): FloatArray {
        val modelInput = frontend.toModelInput(sentence, LANGUAGE)
        // ASSUMED real ONNX graph input/output names (no confirmed export available in this
        // module) — see class doc. Kept as named constants below so a real graph's names can
        // be dropped in by changing them in one place.
        val inputs =
            mapOf(
                INPUT_IDS_KEY to Tensor.longs(modelInput.tokenIds, intArrayOf(1, modelInput.tokenIds.size)),
                SPEAKER_ID_KEY to Tensor.longs(longArrayOf(speakerIndex)),
                SPEED_KEY to Tensor.scalarFloat(speed),
            )
        val outputs = activeSession.run(inputs)
        return outputs[WAVEFORM_KEY]?.asFloats()
            ?: error("session did not return a '$WAVEFORM_KEY' output tensor")
    }

    private fun resolveSpeakerIndex(
        descriptor: ModelDescriptor,
        voiceId: String,
    ): Long {
        val index = descriptor.voices.indexOfFirst { it.id == voiceId }
        require(index >= 0) { "voice '$voiceId' is not among ${descriptor.modelId}'s voices" }
        return index.toLong()
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        modelFile: String,
        speakerNames: List<String>,
        origin: Origin,
    ): ModelDescriptor {
        val voices = speakerNames.mapIndexed { index, name -> Voice(index.toString(), name, LANGUAGE) }
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = origin,
            // 24000 Hz is fixed across all KittenTTS variants (docs/research/model-facts.md,
            // verified) — this is the descriptor layer, the one sanctioned place for it (spec
            // §5.7); nothing outside this engine may repeat this literal.
            sampleRate = SAMPLE_RATE,
            voices = voices,
            speedRange = SPEED_RANGE,
            defaultVoiceId = voices.first().id,
            defaultSpeed = DEFAULT_SPEED,
            assetPaths =
                mapOf(
                    MODEL_ASSET_KEY to resolvePath(bundle, modelFile),
                    CONFIG_ASSET_KEY to resolvePath(bundle, CONFIG_FILE),
                    VOICES_ASSET_KEY to resolvePath(bundle, VOICES_FILE),
                ),
        )
    }

    private fun resolvePath(
        bundle: ModelBundle,
        fileName: String,
    ): String {
        val root = bundle.rootPath?.trimEnd('/')
        return if (root.isNullOrBlank()) fileName else "$root/$fileName"
    }

    /**
     * Minimal parser for the ASSUMED [VOICES_FILE] format: a flat JSON array of quoted speaker
     * name strings, e.g. `["Bella","Jasper"]`. Deliberately not a general JSON parser (this
     * module takes no JSON dependency) — it only ever needs to pull quoted strings out of a
     * top-level array, never an object with keys of its own.
     */
    private fun parseSpeakerNames(raw: String): List<String> =
        QUOTED_STRING.findAll(raw).map { it.groupValues[1] }.toList()

    companion object {
        const val ENGINE_ID = "kittentts"
        const val DISPLAY_NAME = "KittenTTS"

        /** id of the [com.phonetts.core.runtime.Runtime] this engine runs on (spec: ONNX for KittenTTS). */
        const val RUNTIME_ID = "onnx"

        const val SAMPLE_RATE = 24_000

        // "Common range: 0.5-1.5 (adjustable per-speaker)" per docs/research/model-facts.md.
        val SPEED_RANGE = 0.5f..1.5f
        const val DEFAULT_SPEED = 1.0f

        // English-only dev-preview as of v0.8 (docs/research/model-facts.md) — ASSUMPTION that
        // may need to become descriptor-driven if upstream adds languages.
        const val LANGUAGE = "en"

        const val MODEL_SUFFIX = ".onnx"
        const val CONFIG_FILE = "config.json"
        const val VOICES_FILE = "voices.json"
        private const val CONFIG_MARKER = "kitten_tts"

        const val MODEL_ASSET_KEY = "model"
        const val CONFIG_ASSET_KEY = "config"
        const val VOICES_ASSET_KEY = "voices"

        // ASSUMED ONNX graph tensor names (see class doc) — not confirmed against a real export.
        const val INPUT_IDS_KEY = "input_ids"
        const val SPEAKER_ID_KEY = "speaker_id"
        const val SPEED_KEY = "speed"
        const val WAVEFORM_KEY = "waveform"

        /** Family default used by [forcedMatch] when no real speaker table is present. */
        private val DEFAULT_FAMILY_VOICES = listOf("Default")

        private val QUOTED_STRING = Regex("\"([^\"]*)\"")
    }
}
