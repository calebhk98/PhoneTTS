package com.phonetts.engines.executorch

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
import com.phonetts.core.model.ResourceCost
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.closeAllQuietly
import com.phonetts.engines.common.floatsOrError
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.openWithRollback
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.requireVoiceIndex
import java.io.File

/**
 * Kokoro running on the ExecuTorch runtime instead of ONNX (issue: add an ExecuTorch inference
 * runtime + engine). Where `:engines:kokoro`'s ONNX export is ONE graph, VERIFIED (Hugging Face
 * `software-mansion/react-native-executorch-kokoro`) the ExecuTorch export is TWO: a duration
 * predictor (`xnnpack/<variant>/duration_predictor_*.pte`) that turns tokens + a style vector into
 * per-token durations, and a synthesizer (`xnnpack/<variant>/synthesizer_*.pte`) that expands those
 * durations into audio. Both run on the shared "executorch" [com.phonetts.core.runtime.Runtime]
 * (this module's own [ExecuTorchKokoroFrontend] handles text -> token ids); its own
 * [DurationExpansion] bridges the two graphs' outputs/inputs in pure Kotlin.
 *
 * The `voices/<name>.bin` layout is VERIFIED identical to `:engines:kokoro`'s ONNX export ([510,
 * 256] raw little-endian float32, no header — [ExecuTorchKokoroVoiceBinReader]), so voice mixing
 * (issue #42) works the same way: [VoiceBlend] of two loaded tables IS the in-between voice.
 *
 * FLAGGED, not silently assumed correct — three things this engine could not verify without the
 * real AAR + a device (see `engines/executorch/INTEGRATION.md`):
 *  1. `text_mask`'s real dtype is BOOL; ExecuTorch's public Java `Tensor` API exposes no bool
 *     factory (see `com.phonetts.app.runtime.ExecuTorchRuntime`'s kdoc), so it is fed here as an
 *     all-true INT64 tensor — UNVALIDATED.
 *  2. [MAX_DURATION] and the `forward_128` method name are copied from the `kokoro-export` demo
 *     script, not independently reverified against this specific `.pte` export.
 *  3. The reference pipeline's silence-trimming post-process (`find_voice_bound`) is NOT
 *     replicated — this engine returns the synthesizer's raw output (spec TDD note: test the
 *     plumbing, not the audio).
 */
internal class ExecuTorchKokoroEngine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable without real files on disk (parity with
    // KokoroEngine's fileReader seam) -- reads BYTES since voices/*.bin are binary.
    private val fileReader: (path: String) -> ByteArray = { File(it).readBytes() },
    // A second, TEXT reader for vocab.json, mirroring how KokoroEngine reads tokenizer.json.
    private val textFileReader: (path: String) -> String = { File(it).readText() },
) : AbstractVoiceEngine(context),
    BlendableVoices {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private var frontend: TextFrontend? = null
    private var durationSession: InferenceSession? = null
    private var synthesizerSession: InferenceSession? = null
    private var voiceTables: Map<String, FloatArray> = emptyMap()
    private var voiceLanguages: Map<String, String> = emptyMap()
    private var loadedVoices: List<Voice> = emptyList()

    override fun isLoaded(): Boolean = durationSession != null && synthesizerSession != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val durationFile = durationFileIn(bundle) ?: return null
        val synthFile = synthesizerFileIn(bundle) ?: return null
        val manifest = readManifest(bundle) ?: return null
        val descriptor =
            buildDescriptor(bundle, durationFile, synthFile, manifest.config, manifest.voiceIds, Origin.BUILT_IN)
        return EngineMatch(id, descriptor)
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val durationFile =
            durationFileIn(bundle) ?: throw IllegalArgumentException(
                "ExecuTorch Kokoro forcedMatch: bundle '${bundle.id}' has no $DURATION_MARKER $PTE_SUFFIX file",
            )
        val synthFile =
            synthesizerFileIn(bundle) ?: throw IllegalArgumentException(
                "ExecuTorch Kokoro forcedMatch: bundle '${bundle.id}' has no $SYNTH_MARKER $PTE_SUFFIX file",
            )

        val manifest = readManifest(bundle)
        val descriptor =
            if (manifest != null) {
                buildDescriptor(bundle, durationFile, synthFile, manifest.config, manifest.voiceIds, Origin.SIDELOADED)
            } else {
                fallbackDescriptor(bundle, durationFile, synthFile)
            }
        return EngineMatch(id, descriptor)
    }

    private fun durationFileIn(bundle: ModelBundle): String? =
        bundle.fileNames.firstOrNull { it.contains(DURATION_MARKER) && it.endsWith(PTE_SUFFIX) }

    private fun synthesizerFileIn(bundle: ModelBundle): String? =
        bundle.fileNames.firstOrNull { it.contains(SYNTH_MARKER) && it.endsWith(PTE_SUFFIX) }

    /**
     * Reads+validates the config companion file (spec §9.1 fail-closed): a family marker naming
     * this engine, a `vocab.json` present (see [ExecuTorchKokoroVocab]'s kdoc for why this engine
     * requires it), and at least one `voices/<name>.bin` file present BY NAME. Null if any signal
     * is missing/foreign.
     */
    private fun readManifest(bundle: ModelBundle): Manifest? {
        val configText = bundle.sideFile(CONFIG_FILE) ?: return null
        val config = ExecuTorchKokoroConfig.parse(configText)
        val family = config.family?.lowercase() ?: return null
        if (family !in FAMILY_MARKERS) return null
        if (!bundle.hasFile(VOCAB_FILE)) return null

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
        val config: ExecuTorchKokoroConfig.Parsed,
        val voiceIds: List<String>,
    )

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        val durationPath = requireAssetPath(descriptor, DURATION_ASSET, engineLabel)
        val synthPath = requireAssetPath(descriptor, SYNTHESIZER_ASSET, engineLabel)

        closeAllQuietly(durationSession, synthesizerSession)
        durationSession = null
        synthesizerSession = null
        openWithRollback { opened ->
            // VALIDATED (kokoro-export): the duration predictor is invoked via its bounded-dynamic
            // "forward_128" method, not the default "forward" the synthesizer uses.
            val duration =
                runtime.createSession(durationPath, RuntimeOptions(extras = mapOf(METHOD_EXTRA to DURATION_METHOD)))
            opened.add(duration)
            val synth = runtime.createSession(synthPath)
            opened.add(synth)
            durationSession = duration
            synthesizerSession = synth
        }

        frontend = ExecuTorchKokoroFrontend(loadVocab(descriptor), context.phonemizer)
        loadVoiceTable(descriptor)
    }

    private fun loadVocab(descriptor: ModelDescriptor): Map<String, Long> {
        val vocabPath = descriptor.assetPaths[VOCAB_ASSET] ?: return emptyMap()
        return ExecuTorchKokoroVocab.parse(textFileReader(vocabPath))
    }

    override fun unload() {
        closeAllQuietly(durationSession, synthesizerSession)
        durationSession = null
        synthesizerSession = null
        frontend = null
        voiceTables = emptyMap()
        voiceLanguages = emptyMap()
        loadedVoices = emptyList()
    }

    override fun voices(): List<Voice> = loadedVoices

    /** Voice mixing (issue #42): same rationale as `:engines:kokoro` — see class kdoc. */
    override fun addBlendedVoice(spec: BlendedVoiceSpec): Voice? {
        val a = voiceTables[spec.voiceAId] ?: return null
        val b = voiceTables[spec.voiceBId] ?: return null
        val language = voiceLanguages[spec.voiceAId] ?: ExecuTorchKokoroVoiceTable.DEFAULT_LANGUAGE
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
        val notLoaded = "$engineLabel.synthesizeSentence called before load()"
        val duration = durationSession ?: error(notLoaded)
        val synth = synthesizerSession ?: error(notLoaded)
        val activeFrontend = frontend ?: error(notLoaded)
        requireVoiceIndex(loadedVoices, voiceId, engineLabel)

        val table = voiceTables.getValue(voiceId)
        val language = voiceLanguages[voiceId] ?: ExecuTorchKokoroVoiceTable.DEFAULT_LANGUAGE
        val tokenIds = activeFrontend.toModelInput(sentence, language).tokenIds
        val paddedLength = tokenIds.size
        val innerTokenCount = (paddedLength - ExecuTorchKokoroFrontend.PAD_COUNT).coerceAtLeast(0)
        val voiceRow = ExecuTorchKokoroVoiceBinReader.voiceRow(table, innerTokenCount)
        // torch.ones((1, input_length), dtype=bool) in the reference -- see class kdoc item 1 for
        // why this is fed as INT64 rather than a true bool tensor.
        val textMask = LongArray(paddedLength) { TRUE_MASK_VALUE }
        val styleSlice = ExecuTorchKokoroVoiceBinReader.styleSlice(voiceRow)

        val durationInputs = ExecuTorchKokoroTensors.durationInputs(tokenIds, textMask, styleSlice, speed)
        val durationOutputs = duration.run(durationInputs)
        val expanded = ExecuTorchKokoroTensors.expandDuration(durationOutputs, paddedLength, MAX_DURATION, engineLabel)

        val synthInputs = ExecuTorchKokoroTensors.synthesizerInputs(tokenIds, textMask, expanded, voiceRow)
        val synthOutputs = synth.run(synthInputs)
        return synthOutputs.floatsOrError(AUDIO_OUTPUT, engineLabel)
    }

    private fun loadVoiceTable(descriptor: ModelDescriptor) {
        val voicesDirPath = descriptor.assetPaths[VOICES_DIR_ASSET]
        val entries = if (voicesDirPath != null) readEntries(voicesDirPath, descriptor.voices) else emptyList()

        if (entries.isEmpty()) {
            // Sideloaded/forced-match bundle with no real voices/*.bin files on disk: fall back to
            // a single zero-filled "default" table, matching KokoroEngine's own fallback.
            voiceTables = mapOf(descriptor.defaultVoiceId to FloatArray(FALLBACK_TABLE_SIZE))
            voiceLanguages = mapOf(descriptor.defaultVoiceId to ExecuTorchKokoroVoiceTable.DEFAULT_LANGUAGE)
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
    ): List<ExecuTorchKokoroVoiceTable.Entry> {
        val voiceFiles = voices.associate { it.id to fileReader("$voicesDirPath/${it.id}$BIN_SUFFIX") }
        return ExecuTorchKokoroVoiceTable.parse(voiceFiles)
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        durationFile: String,
        synthFile: String,
        config: ExecuTorchKokoroConfig.Parsed,
        voiceIds: List<String>,
        origin: Origin,
    ): ModelDescriptor {
        val voices = voiceIds.map { ExecuTorchKokoroVoiceTable.voiceFor(it) }
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
            // Introspected: the duration predictor's native "speed" input is this model's real
            // speed knob (CLAUDE.md rule 2) -- never resampled. Bounds are ASSUMED (see class kdoc)
            // when config.json omits them, exactly like KokoroEngine's own ONNX fallback.
            parameters = listOf(ModelParameter.speed(speedRange, defaultSpeed)),
            // Both graphs consume a continuous 256-wide voice vector (the same table
            // KokoroEngine's ONNX export blends), so two voices interpolate into an in-between one.
            supportsVoiceBlend = true,
            assetPaths =
                mapOf(
                    DURATION_ASSET to joinAssetPath(bundle, durationFile),
                    SYNTHESIZER_ASSET to joinAssetPath(bundle, synthFile),
                    VOICES_DIR_ASSET to joinAssetPath(bundle, VOICES_DIR),
                    VOCAB_ASSET to joinAssetPath(bundle, VOCAB_FILE),
                ),
            resourceCost = ResourceCost.peakRamMebibytes(PEAK_RAM_MIB),
        )
    }

    private fun fallbackDescriptor(
        bundle: ModelBundle,
        durationFile: String,
        synthFile: String,
    ): ModelDescriptor {
        val fallbackVoice = Voice(FALLBACK_VOICE_ID, FALLBACK_VOICE_NAME, ExecuTorchKokoroVoiceTable.DEFAULT_LANGUAGE)
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
            assetPaths =
                mapOf(
                    DURATION_ASSET to joinAssetPath(bundle, durationFile),
                    SYNTHESIZER_ASSET to joinAssetPath(bundle, synthFile),
                ),
            resourceCost = ResourceCost.peakRamMebibytes(PEAK_RAM_MIB),
        )
    }

    companion object {
        const val ENGINE_ID = "executorch-kokoro"
        const val DISPLAY_NAME = "Kokoro (ExecuTorch)"

        // A-priori peak-RAM estimate: the real repo's "standard" .pte pair is ~62 MB
        // (duration_predictor) + ~272 MB (synthesizer) on disk (VERIFIED, HF file listing);
        // resident RAM while both are loaded and generating is assumed somewhat higher.
        private const val PEAK_RAM_MIB = 420L

        // Companion-file names inspect()/forcedMatch() fingerprint a bundle by.
        private const val CONFIG_FILE = "config.json"

        // Our own convention -- see ExecuTorchKokoroVocab's kdoc for why the real HF repo ships none.
        const val VOCAB_FILE = "vocab.json"

        // Either our own curated "family"/"model_name": "executorch-kokoro", or the real repo's
        // literal config.json shape {"modelName": "kokoro"}.
        private val FAMILY_MARKERS = setOf("kokoro", "executorch-kokoro")

        private const val PTE_SUFFIX = ".pte"
        private const val DURATION_MARKER = "duration_predictor"
        private const val SYNTH_MARKER = "synthesizer"

        // VERIFIED (HF react-native-executorch-kokoro `voices/` listing): same directory
        // convention as the ONNX export, one `<name>.bin` per voice.
        const val VOICES_DIR = "voices"
        private const val VOICES_DIR_PREFIX = "$VOICES_DIR/"
        private const val BIN_SUFFIX = ".bin"

        // Logical asset-path keys populated into ModelDescriptor.assetPaths.
        private const val DURATION_ASSET = "durationPredictor"
        private const val SYNTHESIZER_ASSET = "synthesizer"
        const val VOICES_DIR_ASSET = "voicesDir"
        const val VOCAB_ASSET = "vocab"

        private const val RUNTIME_ID = "executorch"

        // Cross-module string contract with com.phonetts.app.runtime.ExecuTorchRuntime (this
        // module cannot depend on :app, nor vice versa) -- both literals must stay in sync; see
        // engines/executorch/INTEGRATION.md.
        private const val METHOD_EXTRA = "method"
        private const val DURATION_METHOD = "forward_128"

        private const val TRUE_MASK_VALUE = 1L

        // Kokoro-82M base-model fact (same verified value :engines:kokoro's SAMPLE_RATE uses),
        // used only when config.json omits sample_rate.
        private const val SAMPLE_RATE = 24_000

        private const val FALLBACK_SPEED_MIN = 0.5f
        private const val FALLBACK_SPEED_MAX = 2.0f
        private const val FALLBACK_DEFAULT_SPEED = 1.0f
        private const val FALLBACK_VOICE_ID = "default"
        private const val FALLBACK_VOICE_NAME = "Default"
        private const val FALLBACK_TABLE_SIZE =
            ExecuTorchKokoroVoiceBinReader.ROWS * ExecuTorchKokoroVoiceBinReader.COLS

        // ASSUMED (kokoro-export inference_example.py `MAX_DURATION = 296`) -- see class kdoc item 2.
        private const val MAX_DURATION = 296

        // Tensor names/shapes and the "output0"/"output1" positional-key convention live in
        // ExecuTorchKokoroTensors, alongside the functions that build/read them.
        private val AUDIO_OUTPUT = ExecuTorchKokoroTensors.AUDIO_OUTPUT
    }
}
