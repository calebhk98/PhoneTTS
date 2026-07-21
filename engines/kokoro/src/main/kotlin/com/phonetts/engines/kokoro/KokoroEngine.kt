package com.phonetts.engines.kokoro

import com.phonetts.core.engine.BlendableVoices
import com.phonetts.core.engine.BlendedVoiceSpec
import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.engine.Voice
import com.phonetts.core.engine.VoiceBlend
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter
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
 * voices selected by a per-voice **style table** rather than per-file weights
 * (docs/research/model-facts.md) — so, unlike a one-voice-per-file model, loading this engine
 * means loading one graph plus one table PER voice, and synthesis means picking one row out of
 * the active voice's table per sentence.
 *
 * VALIDATED against the REAL `onnx-community/Kokoro-82M-v1.0-ONNX` repo, proven end-to-end in
 * `scripts/model-verify/run_kokoro.py` (11s of clean audio, fp32 `onnx/model.onnx` — the q8f16
 * quantized export segfaults onnxruntime, so this engine only ever asks for the fp32 weights
 * file): voices are NOT a `voices.json` name->vector table. They are per-voice files
 * `voices/<name>.bin`, each a raw little-endian float32 array of shape [510, 256]
 * ([KokoroVoiceBinReader]). The style fed to the model for a given sentence is the single row
 * indexed by that sentence's token count: `row = table[min(tokenCount, 509)]`
 * ([KokoroVoiceBinReader.styleRow]).
 *
 * Runs on the shared "onnx" [com.phonetts.core.runtime.Runtime] (spec §5.3); its own
 * [KokoroFrontend] handles text -> token ids.
 */
internal class KokoroEngine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable without real files on disk (parity with
    // KittenEngine's fileReader seam) -- here reading BYTES rather than text, since voices/*.bin
    // are binary, raw little-endian float32 files with no numpy header.
    private val fileReader: (path: String) -> ByteArray = { File(it).readBytes() },
    // A second, TEXT reader for tokenizer.json (the phoneme vocabulary) -- read as UTF-8 text at
    // load() and parsed by KokoroVocab, mirroring how MeloEngine reads its tokens.txt/lexicon.txt.
    private val textFileReader: (path: String) -> String = { File(it).readText() },
) : AbstractVoiceEngine(context),
    BlendableVoices {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    // Set at load() once the bundle's tokenizer.json vocabulary is known (like Melo/Kitten), never
    // a final field: the phoneme table lives in the model bundle, not this jar.
    private var frontend: TextFrontend? = null

    private var session: InferenceSession? = null
    private var voiceTables: Map<String, FloatArray> = emptyMap()
    private var voiceLanguages: Map<String, String> = emptyMap()
    private var loadedVoices: List<Voice> = emptyList()

    override fun isLoaded(): Boolean = session != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val weightsFile = bundle.fileNames.firstOrNull { it.endsWith(WEIGHTS_SUFFIX) } ?: return null
        val manifest = readManifest(bundle) ?: return null
        val descriptor = buildDescriptor(bundle, weightsFile, manifest.config, manifest.voiceIds, Origin.BUILT_IN)

        return EngineMatch(id, descriptor)
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
                buildDescriptor(bundle, weightsFile, manifest.config, manifest.voiceIds, Origin.SIDELOADED)
            } else {
                fallbackDescriptor(bundle, weightsFile)
            }
        return EngineMatch(id, descriptor)
    }

    /**
     * Reads+validates the config companion file and fingerprints the voice table by the presence
     * of `voices/<name>.bin` files BY NAME (spec §9.1 fail-closed): [ModelBundle] only carries small
     * *text* side files for fingerprinting, so `voices/<name>.bin` — binary, no header — is never
     * readable here, only listed. That is enough: unlike KittenTTS's single zipped `voices.npz`
     * (whose per-voice entries are invisible until the archive is opened), each Kokoro voice is
     * its own named file, so the real voice ids are already known from [ModelBundle.fileNames]
     * without touching any bytes. Null if the config is missing/foreign or no voice files exist.
     */
    private fun readManifest(bundle: ModelBundle): Manifest? {
        val configText = bundle.sideFile(CONFIG_FILE) ?: return null
        val config = KokoroConfig.parse(configText)
        if (config.family !in FAMILY_MARKERS) return null
        // A real Kokoro bundle ALWAYS ships tokenizer.json (the phoneme vocabulary); without it the
        // frontend can't turn IPA into token ids, so refuse rather than load a model that would only
        // synthesize garbage (fail closed, spec §9.1 / rule 4).
        if (!bundle.hasFile(TOKENIZER_FILE)) return null

        val voiceIds = voiceIdsIn(bundle)
        if (voiceIds.isEmpty()) return null

        return Manifest(config, voiceIds)
    }

    private fun voiceIdsIn(bundle: ModelBundle): List<String> =
        bundle.fileNames
            .filter { it.startsWith(VOICES_DIR_PREFIX) && it.endsWith(BIN_SUFFIX) }
            .map { it.removePrefix(VOICES_DIR_PREFIX).removeSuffix(BIN_SUFFIX) }
            .sorted()

    private data class Manifest(
        val config: KokoroConfig.Parsed,
        val voiceIds: List<String>,
    )

    override suspend fun load(descriptor: ModelDescriptor) {
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        val weightsPath = requireAssetPath(descriptor, WEIGHTS_ASSET, engineLabel)

        session?.close()
        session = runtime.createSession(weightsPath)

        frontend = KokoroFrontend(loadVocab(descriptor), context.phonemizer)
        loadVoiceTable(descriptor)
    }

    /**
     * Reads+parses the bundle's `tokenizer.json` phoneme vocabulary (via [textFileReader]). A
     * sideloaded/forced-match bundle that shipped no tokenizer file has no [TOKENIZER_ASSET] path;
     * that yields an empty vocab (the frontend then emits pad-only input) rather than crashing load.
     */
    private fun loadVocab(descriptor: ModelDescriptor): Map<String, Long> {
        val tokenizerPath = descriptor.assetPaths[TOKENIZER_ASSET] ?: return emptyMap()
        return KokoroVocab.parse(textFileReader(tokenizerPath))
    }

    override fun unload() {
        closeAllQuietly(session)
        session = null
        frontend = null
        voiceTables = emptyMap()
        voiceLanguages = emptyMap()
        loadedVoices = emptyList()
    }

    override fun voices(): List<Voice> = loadedVoices

    /**
     * Voice mixing (issue #42): Kokoro's StyleTTS2 graph feeds a continuous style row, so the whole
     * [510, 256] style table interpolates element-wise — [VoiceBlend] of two loaded tables IS the
     * in-between voice (blending the flattened table blends every style row it indexes). Registered
     * as a new selectable voice, inheriting voice A's language. Fails closed if either source id
     * isn't loaded.
     */
    override fun addBlendedVoice(spec: BlendedVoiceSpec): Voice? {
        val a = voiceTables[spec.voiceAId] ?: return null
        val b = voiceTables[spec.voiceBId] ?: return null
        val language = voiceLanguages[spec.voiceAId] ?: KokoroVoiceTable.DEFAULT_LANGUAGE
        val voice = Voice(id = spec.id, name = spec.name, language = language)
        voiceTables = voiceTables + (spec.id to VoiceBlend.blend(a, b, spec.weight))
        voiceLanguages = voiceLanguages + (spec.id to language)
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
        val activeFrontend = frontend ?: error("$engineLabel.synthesizeSentence called before load()")
        val table = voiceTables[voiceId] ?: error("Unknown Kokoro voice id '$voiceId'")
        val language = voiceLanguages[voiceId] ?: KokoroVoiceTable.DEFAULT_LANGUAGE

        val modelInput = activeFrontend.toModelInput(sentence, language)
        // VALIDATED against onnx-community/Kokoro-82M-v1.0-ONNX (scripts/model-verify/run_kokoro.py,
        // 11s of clean audio): the graph's inputs are "input_ids" int64 [1, T], "style" float32
        // [1, 256] (ONE row selected from the active voice's [510, 256] table, indexed by token
        // count — KokoroVoiceBinReader.styleRow), "speed" float32 [1]; the single output is
        // "waveform" float32 [1, N].
        // Index by the INNER token count (`len(tokens)` in run_kokoro.py line 23), not the pad-wrapped
        // length: modelInput.tokenIds is `[0, *inner, 0]`, so subtract the two pads styleRow must not see.
        val innerTokenCount = (modelInput.tokenIds.size - KokoroFrontend.PAD_COUNT).coerceAtLeast(0)
        val style = KokoroVoiceBinReader.styleRow(table, innerTokenCount)
        val inputs =
            mapOf(
                TOKENS_INPUT to Tensor.longs(modelInput.tokenIds, intArrayOf(1, modelInput.tokenIds.size)),
                STYLE_INPUT to Tensor.floats(style, intArrayOf(1, style.size)),
                SPEED_INPUT to Tensor.scalarFloat(speed),
            )
        val outputs = activeSession.run(inputs)
        return outputs.floatsOrError(AUDIO_OUTPUT, engineLabel)
    }

    private fun loadVoiceTable(descriptor: ModelDescriptor) {
        val voicesDirPath = descriptor.assetPaths[VOICES_DIR_ASSET]
        val entries = if (voicesDirPath != null) readEntries(voicesDirPath, descriptor.voices) else emptyList()

        if (entries.isEmpty()) {
            // Sideloaded/forced-match bundle with no real voices/*.bin files on disk: fall back to
            // a single zero-filled "default" table so synthesize() still has something to feed the
            // graph (styleRow's indexing stays valid regardless of token count since the fallback
            // is full-sized). Real voice quality for such bundles is out of scope for this seam
            // (spec TDD note: "test the plumbing, not the audio").
            voiceTables = mapOf(descriptor.defaultVoiceId to FloatArray(FALLBACK_TABLE_SIZE))
            voiceLanguages = mapOf(descriptor.defaultVoiceId to KokoroVoiceTable.DEFAULT_LANGUAGE)
            loadedVoices = descriptor.voices
            return
        }

        voiceTables = entries.associate { it.voice.id to it.table }
        voiceLanguages = entries.associate { it.voice.id to it.voice.language }
        loadedVoices = entries.map { it.voice }
    }

    private fun readEntries(
        voicesDirPath: String,
        voices: List<Voice>,
    ): List<KokoroVoiceTable.Entry> {
        val voiceFiles = voices.associate { it.id to fileReader("$voicesDirPath/${it.id}$BIN_SUFFIX") }
        return KokoroVoiceTable.parse(voiceFiles)
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        weightsFile: String,
        config: KokoroConfig.Parsed,
        voiceIds: List<String>,
        origin: Origin,
    ): ModelDescriptor {
        val voices = voiceIds.map { KokoroVoiceTable.voiceFor(it) }
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
            defaultVoiceId = defaultVoiceId,
            // Introspected: Kokoro's StyleTTS2 graph has a native "speed" input, and its bounds come
            // from the model's own config.json (speed_min/max/default) — a per-model fact, not a
            // constant. Routed to that scalar, never resampled (CLAUDE.md rule 2).
            parameters = listOf(ModelParameter.speed(speedRange, defaultSpeed)),
            // Introspected: the StyleTTS2 graph takes a continuous style vector, so two voices'
            // style tables interpolate into an in-between one (issue #42). A descriptor fact the
            // "mix voices" UI derives from — never a model-name special case (rule 5).
            supportsVoiceBlend = true,
            assetPaths =
                mapOf(
                    WEIGHTS_ASSET to joinAssetPath(bundle, weightsFile),
                    VOICES_DIR_ASSET to joinAssetPath(bundle, VOICES_DIR),
                    TOKENIZER_ASSET to joinAssetPath(bundle, TOKENIZER_FILE),
                ),
        )
    }

    private fun fallbackDescriptor(
        bundle: ModelBundle,
        weightsFile: String,
    ): ModelDescriptor {
        val fallbackVoice = Voice(FALLBACK_VOICE_ID, FALLBACK_VOICE_NAME, KokoroVoiceTable.DEFAULT_LANGUAGE)
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = Origin.SIDELOADED,
            sampleRate = SAMPLE_RATE,
            voices = listOf(fallbackVoice),
            defaultVoiceId = fallbackVoice.id,
            parameters = listOf(ModelParameter.speed(FALLBACK_SPEED_MIN..FALLBACK_SPEED_MAX, FALLBACK_DEFAULT_SPEED)),
            supportsVoiceBlend = true,
            assetPaths = mapOf(WEIGHTS_ASSET to joinAssetPath(bundle, weightsFile)),
        )
    }

    companion object {
        const val ENGINE_ID = "kokoro"
        const val DISPLAY_NAME = "Kokoro"

        // Companion-file names inspect()/forcedMatch() fingerprint a bundle by.
        private const val CONFIG_FILE = "config.json"
        private const val WEIGHTS_SUFFIX = ".onnx"

        // The phoneme vocabulary file every real Kokoro bundle ships (onnx-community/
        // Kokoro-82M-v1.0-ONNX). Required by inspect() and read as text at load() into KokoroVocab.
        private const val TOKENIZER_FILE = "tokenizer.json"

        // Either our own curated "family": "kokoro" or the real onnx-community export's
        // "model_type": "style_text_to_speech_2" (StyleTTS2) identifies a Kokoro bundle.
        private val FAMILY_MARKERS = setOf("kokoro", "style_text_to_speech_2")

        // VALIDATED (scripts/model-verify/run_kokoro.py): the real per-voice files live under this
        // directory, one `<name>.bin` per voice -- not a single `voices.json`/`voices.npz` table.
        const val VOICES_DIR = "voices"
        private const val VOICES_DIR_PREFIX = "$VOICES_DIR/"
        private const val BIN_SUFFIX = ".bin"

        // Logical asset-path keys populated into ModelDescriptor.assetPaths.
        private const val WEIGHTS_ASSET = "weights"
        const val VOICES_DIR_ASSET = "voicesDir"
        const val TOKENIZER_ASSET = "tokenizer"

        private const val RUNTIME_ID = "onnx"

        // Verified fact (docs/research/model-facts.md): Kokoro-82M is 24000 Hz. Used only as a
        // fallback when a bundle's config.json omits sample_rate; the descriptor is still the
        // single source of truth callers read from.
        private const val SAMPLE_RATE = 24_000

        private const val FALLBACK_SPEED_MIN = 0.5f
        private const val FALLBACK_SPEED_MAX = 2.0f
        private const val FALLBACK_DEFAULT_SPEED = 1.0f
        private const val FALLBACK_VOICE_ID = "default"
        private const val FALLBACK_VOICE_NAME = "Default"
        private const val FALLBACK_TABLE_SIZE = KokoroVoiceBinReader.ROWS * KokoroVoiceBinReader.COLS

        // Validated ONNX graph tensor names (onnx-community/Kokoro-82M-v1.0-ONNX, confirmed via
        // scripts/model-verify/run_kokoro.py).
        private const val TOKENS_INPUT = "input_ids"
        private const val STYLE_INPUT = "style"
        private const val SPEED_INPUT = "speed"
        private const val AUDIO_OUTPUT = "waveform"
    }
}
