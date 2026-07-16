package com.phonetts.engines.kittentts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.closeAllQuietly
import com.phonetts.engines.common.floatsOrError
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.json.parseStringArray
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.requireVoiceIndex
import com.phonetts.engines.common.sideFileContainsMarker

/**
 * KittenTTS (KittenML/KittenTTS) engine: a single ONNX graph with ~8 built-in speakers, no
 * per-voice files (spec §5.7 table; docs/research/model-facts.md). Dev-preview, Apache-2.0,
 * English-only as of v0.8 — [LANGUAGE] is hardcoded to "en" for that reason and will need to
 * become descriptor-driven if/when upstream adds languages.
 *
 * Companion-file fingerprint used by [inspect] (spec §6.2 / §9.1 — fail closed):
 *  - a `.onnx` weights file,
 *  - a [CONFIG_FILE] side file whose contents mention [CONFIG_MARKER] (ASSUMPTION: real
 *    KittenTTS config.json carries a "model_type"/"architecture" field identifying the family;
 *    we key off a substring match via the shared `sideFileContainsMarker` primitive rather than
 *    a full field-by-field JSON parse),
 *  - a [VOICES_FILE] side file: ASSUMED to be a flat JSON array of speaker-name strings, e.g.
 *    `["Bella","Jasper","Luna","Bruno","Rosie","Hugo","Kiki","Leo"]` (the 8 names KittenTTS
 *    ships per model-facts.md), read via the shared `com.phonetts.engines.common.json.MiniJson`
 *    reader's `parseStringArray`. A bare `.onnx` with none of these companions, or a bundle
 *    whose config doesn't carry the marker, is refused — never guessed.
 */
internal class KittenEngine(context: EngineContext) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private val frontend = KittenFrontend(context.phonemizer)
    private var session: InferenceSession? = null
    private var loadedDescriptor: ModelDescriptor? = null

    override fun isLoaded(): Boolean = session != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val modelFile = bundle.fileNames.firstOrNull { it.endsWith(MODEL_SUFFIX) } ?: return null
        if (!bundle.sideFileContainsMarker(CONFIG_FILE, CONFIG_MARKER)) return null

        val voicesRaw = bundle.sideFile(VOICES_FILE) ?: return null
        val speakerNames = parseStringArray(voicesRaw)
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
            bundle.sideFile(VOICES_FILE)?.let(::parseStringArray)?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_FAMILY_VOICES

        return EngineMatch(id, buildDescriptor(bundle, modelFile, speakerNames, Origin.SIDELOADED))
    }

    override suspend fun load(descriptor: ModelDescriptor) {
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        val modelPath = requireAssetPath(descriptor, MODEL_ASSET_KEY, engineLabel)

        session?.close()
        session = runtime.createSession(modelPath)
        loadedDescriptor = descriptor
    }

    override fun unload() {
        closeAllQuietly(session)
        session = null
        loadedDescriptor = null
    }

    override fun voices(): List<Voice> = loadedDescriptor?.voices ?: emptyList()

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        speed: Float,
    ): FloatArray {
        val activeSession = checkNotNull(session) { "$engineLabel.synthesizeSentence called before load()" }
        val descriptor = checkNotNull(loadedDescriptor) { "$engineLabel.synthesizeSentence called before load()" }
        val speakerIndex = requireVoiceIndex(descriptor.voices, voiceId, engineLabel).toLong()

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
        return outputs.floatsOrError(WAVEFORM_KEY, engineLabel)
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
                    MODEL_ASSET_KEY to joinAssetPath(bundle, modelFile),
                    CONFIG_ASSET_KEY to joinAssetPath(bundle, CONFIG_FILE),
                    VOICES_ASSET_KEY to joinAssetPath(bundle, VOICES_FILE),
                ),
        )
    }

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
    }
}
