package com.phonetts.engines.kokoro

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.TextFrontend
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
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import java.io.File

/**
 * The Kokoro-82M engine (spec Phase 2, this ticket: Phase 2.5). StyleTTS2-based, 24 kHz,
 * 54 voices across 8 languages selected by a per-voice **style embedding** rather than
 * per-file weights (docs/research/model-facts.md) — so, unlike a one-voice-per-file model,
 * loading this engine means loading one graph plus a whole embeddings table, and synthesis
 * means picking one row out of that table per call.
 *
 * Runs on the shared "onnx" [com.phonetts.core.runtime.Runtime] (spec §5.3); its own
 * [KokoroFrontend] handles text -> token ids.
 */
internal class KokoroEngine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable without real files on disk (parity with
    // PiperEngine's sidecarReader). Defaults to a real file read.
    private val fileReader: (path: String) -> String = { File(it).readText() },
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private val frontend: TextFrontend = KokoroFrontend(context.phonemizer)

    private var session: InferenceSession? = null
    private var voiceEmbeddings: Map<String, FloatArray> = emptyMap()
    private var voiceLanguages: Map<String, String> = emptyMap()
    private var loadedVoices: List<Voice> = emptyList()

    override fun isLoaded(): Boolean = session != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val weightsFile = bundle.fileNames.firstOrNull { it.endsWith(WEIGHTS_SUFFIX) } ?: return null
        val manifest = readManifest(bundle) ?: return null

        return EngineMatch(id, buildDescriptor(bundle, weightsFile, manifest.config, manifest.entries, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val weightsFile =
            bundle.fileNames.firstOrNull { it.endsWith(WEIGHTS_SUFFIX) }
                ?: throw IllegalArgumentException(
                    "Kokoro forcedMatch: bundle '${bundle.id}' has no $WEIGHTS_SUFFIX weights file",
                )

        val manifest = readManifest(bundle)
        val descriptor =
            if (manifest != null) {
                buildDescriptor(bundle, weightsFile, manifest.config, manifest.entries, Origin.SIDELOADED)
            } else {
                fallbackDescriptor(bundle, weightsFile)
            }
        return EngineMatch(id, descriptor)
    }

    /** Reads+validates the config+voices-table companion files. Null if either is missing/unrecognized. */
    private fun readManifest(bundle: ModelBundle): Manifest? {
        val configText = bundle.sideFile(CONFIG_FILE) ?: return null
        val voicesText = bundle.sideFile(VOICES_FILE) ?: return null

        val config = KokoroConfig.parse(configText)
        if (config.family != FAMILY_MARKER) return null

        val entries = KokoroVoiceTable.parse(voicesText)
        if (entries.isEmpty()) return null

        return Manifest(config, entries)
    }

    private data class Manifest(
        val config: KokoroConfig.Parsed,
        val entries: List<KokoroVoiceTable.Entry>,
    )

    override suspend fun load(descriptor: ModelDescriptor) {
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        val weightsPath = requireAssetPath(descriptor, WEIGHTS_ASSET, engineLabel)

        session?.close()
        session = runtime.createSession(weightsPath)

        loadVoiceTable(descriptor)
    }

    override fun unload() {
        closeAllQuietly(session)
        session = null
        voiceEmbeddings = emptyMap()
        voiceLanguages = emptyMap()
        loadedVoices = emptyList()
    }

    override fun voices(): List<Voice> = loadedVoices

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        speed: Float,
    ): FloatArray {
        val activeSession = session ?: error("$engineLabel.synthesizeSentence called before load()")
        val embedding = voiceEmbeddings[voiceId] ?: error("Unknown Kokoro voice id '$voiceId'")
        val language = voiceLanguages[voiceId] ?: DEFAULT_LANGUAGE

        val modelInput = frontend.toModelInput(sentence, language)
        // VALIDATED against onnx-community/Kokoro-82M-v1.0-ONNX (inspected 2026-07): the graph's
        // inputs are "input_ids" int64 [1, T], "style" float32 [1, 256] (the selected voice's
        // embedding row), "speed" float32 [1]; the single output is "waveform" float32 [1, N].
        // See docs/research/onnx-io.md for the raw inspection.
        val inputs =
            mapOf(
                TOKENS_INPUT to Tensor.longs(modelInput.tokenIds, intArrayOf(1, modelInput.tokenIds.size)),
                STYLE_INPUT to Tensor.floats(embedding, intArrayOf(1, embedding.size)),
                SPEED_INPUT to Tensor.scalarFloat(speed),
            )
        val outputs = activeSession.run(inputs)
        return outputs.floatsOrError(AUDIO_OUTPUT, engineLabel)
    }

    private fun loadVoiceTable(descriptor: ModelDescriptor) {
        val tablePath = descriptor.assetPaths[VOICES_TABLE_ASSET]
        val entries = tablePath?.let { KokoroVoiceTable.parse(fileReader(it)) }.orEmpty()

        if (entries.isEmpty()) {
            // Sideloaded/forced-match bundle with no real embeddings table on disk: fall back to
            // a single zero-vector "default" style so synthesize() still has something to feed
            // the graph. Real voice quality for such bundles is out of scope for this seam
            // (spec TDD note: "test the plumbing, not the audio").
            voiceEmbeddings = mapOf(descriptor.defaultVoiceId to FloatArray(FALLBACK_EMBEDDING_SIZE))
            voiceLanguages = mapOf(descriptor.defaultVoiceId to DEFAULT_LANGUAGE)
            loadedVoices = descriptor.voices
            return
        }

        voiceEmbeddings = entries.associate { it.voice.id to it.embedding }
        voiceLanguages = entries.associate { it.voice.id to it.voice.language }
        loadedVoices = entries.map { it.voice }
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        weightsFile: String,
        config: KokoroConfig.Parsed,
        entries: List<KokoroVoiceTable.Entry>,
        origin: Origin,
    ): ModelDescriptor {
        val voices = entries.map { it.voice }
        val defaultVoiceId =
            config.defaultVoiceId?.takeIf { candidate -> voices.any { it.id == candidate } }
                ?: voices.first().id
        val speedRange = (config.speedMin ?: FALLBACK_SPEED_MIN)..(config.speedMax ?: FALLBACK_SPEED_MAX)
        val defaultSpeed = (config.defaultSpeed ?: FALLBACK_DEFAULT_SPEED).coerceIn(speedRange)

        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = origin,
            sampleRate = config.sampleRate ?: SAMPLE_RATE,
            voices = voices,
            speedRange = speedRange,
            defaultVoiceId = defaultVoiceId,
            defaultSpeed = defaultSpeed,
            assetPaths =
                mapOf(
                    WEIGHTS_ASSET to joinAssetPath(bundle, weightsFile),
                    VOICES_TABLE_ASSET to joinAssetPath(bundle, VOICES_FILE),
                ),
        )
    }

    private fun fallbackDescriptor(
        bundle: ModelBundle,
        weightsFile: String,
    ): ModelDescriptor {
        val fallbackVoice = Voice(FALLBACK_VOICE_ID, FALLBACK_VOICE_NAME, DEFAULT_LANGUAGE)
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = Origin.SIDELOADED,
            sampleRate = SAMPLE_RATE,
            voices = listOf(fallbackVoice),
            speedRange = FALLBACK_SPEED_MIN..FALLBACK_SPEED_MAX,
            defaultVoiceId = fallbackVoice.id,
            defaultSpeed = FALLBACK_DEFAULT_SPEED,
            assetPaths = mapOf(WEIGHTS_ASSET to joinAssetPath(bundle, weightsFile)),
        )
    }

    companion object {
        const val ENGINE_ID = "kokoro"
        const val DISPLAY_NAME = "Kokoro"

        // Companion-file names inspect()/forcedMatch() fingerprint a bundle by.
        private const val CONFIG_FILE = "config.json"
        private const val VOICES_FILE = "voices.json"
        private const val FAMILY_MARKER = "kokoro"
        private const val WEIGHTS_SUFFIX = ".onnx"

        // Logical asset-path keys populated into ModelDescriptor.assetPaths.
        private const val WEIGHTS_ASSET = "weights"
        private const val VOICES_TABLE_ASSET = "voicesTable"

        private const val RUNTIME_ID = "onnx"
        private const val DEFAULT_LANGUAGE = "en-us"

        // Verified fact (docs/research/model-facts.md): Kokoro-82M is 24000 Hz. Used only as a
        // fallback when a bundle's config.json omits sample_rate; the descriptor is still the
        // single source of truth callers read from.
        private const val SAMPLE_RATE = 24_000

        private const val FALLBACK_SPEED_MIN = 0.5f
        private const val FALLBACK_SPEED_MAX = 2.0f
        private const val FALLBACK_DEFAULT_SPEED = 1.0f
        private const val FALLBACK_VOICE_ID = "default"
        private const val FALLBACK_VOICE_NAME = "Default"
        private const val FALLBACK_EMBEDDING_SIZE = 1

        // Validated ONNX graph tensor names (onnx-community/Kokoro-82M-v1.0-ONNX, inspected 2026-07).
        private const val TOKENS_INPUT = "input_ids"
        private const val STYLE_INPUT = "style"
        private const val SPEED_INPUT = "speed"
        private const val AUDIO_OUTPUT = "waveform"
    }
}
