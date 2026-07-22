package com.phonetts.engines.melotts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.model.Origin
import com.phonetts.core.model.ResourceCost
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.closeAllQuietly
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.openWithRollback
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireExtra
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.requireVoiceIndex
import com.phonetts.engines.common.singleFloatsOrError
import java.io.File

/**
 * MeloTTS engine, retargeted to the MiaoMint/MeloTTS-ONNX sherpa export
 * (`onnx_exports/en_v2`) — PROVEN to produce real, non-silent English speech by
 * `scripts/model-verify/run_melo2.py` (10.54 s, 908 KB, peak 0.293). This replaces the previous
 * seasonstudio-oriented dual-session (BERT + acoustic) engine, which ran shape-correctly but
 * produced silence: its hardcoded 219-symbol table didn't match that export's 112-row embedding.
 *
 * The new contract is a SINGLE acoustic session with SEVEN named inputs — no BERT, no language
 * id, no sdp_ratio:
 *
 * | input            | dtype   | shape   |
 * |------------------|---------|---------|
 * | `x`              | int64   | `[1,L]` | symbol ids, add_blank-interspersed |
 * | `x_lengths`      | int64   | `[1]`   | `L` |
 * | `tones`          | int64   | `[1,L]` | per-symbol tone id, same interspersing |
 * | `sid`            | int64   | `[1]`   | speaker id |
 * | `noise_scale`    | float32 | `[1]`   | fixed 0.6 |
 * | `length_scale`   | float32 | `[1]`   | **the speed control**, `1f / speed` |
 * | `noise_scale_w`  | float32 | `[1]`   | fixed 0.8 |
 *
 * The output is read positionally (`singleFloatsOrError`), since the export auto-numbers it.
 *
 * The model ships its own symbol table (`tokens.txt`) and G2P dictionary (`lexicon.txt`) — the
 * SSOT fix (spec rule 1): those are read from the bundle at [load] time via [fileReader] rather
 * than hardcoded, so a future export with a different vocabulary can never desync from this
 * engine the way the old one did.
 */
internal class MeloEngine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable without real files on disk (parity with
    // KittenEngine's fileReader seam) -- tokens.txt/lexicon.txt are read as text.
    private val fileReader: (path: String) -> String = { File(it).readText() },
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private var acousticSession: InferenceSession? = null
    private var frontend: MeloFrontend? = null
    private var loadedDescriptor: ModelDescriptor? = null

    override fun isLoaded(): Boolean = acousticSession != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val modelFile = bundle.fileNames.firstOrNull { it.endsWith(MODEL_SUFFIX) } ?: return null
        val rootPath = bundle.rootPath ?: return null
        if (!hasMeloCompanionFiles(bundle)) return null
        val metadata =
            bundle.sideFile(METADATA_FILE)?.let(MeloMetadata::parse)?.takeIf { it.isMeloVits() } ?: return null

        val voices = buildVoices(metadata)
        val paths = assetPaths(rootPath, modelFile)
        val descriptor = buildDescriptor(bundle.id, voices, Origin.BUILT_IN, paths, metadata)
        return EngineMatch(ENGINE_ID, descriptor)
    }

    private fun hasMeloCompanionFiles(bundle: ModelBundle): Boolean =
        bundle.hasFile(TOKENS_FILE) && bundle.hasFile(LEXICON_FILE)

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val modelFile = bundle.fileNames.firstOrNull { it.endsWith(MODEL_SUFFIX) }
        requireNotNull(modelFile) { "bundle ${bundle.id} has no MeloTTS acoustic weights ($MODEL_SUFFIX)" }
        val rootPath =
            requireNotNull(bundle.rootPath) {
                "bundle ${bundle.id} has no root path to build MeloTTS asset paths from"
            }

        val metadata = bundle.sideFile(METADATA_FILE)?.let(MeloMetadata::parse)
        val voices =
            metadata?.let(::buildVoices)?.takeIf { it.isNotEmpty() }
                ?: listOf(Voice(DEFAULT_VOICE_ID, DEFAULT_VOICE_NAME, DEFAULT_LANGUAGE))

        val paths = assetPaths(rootPath, modelFile)
        val descriptor = buildDescriptor(bundle.id, voices, Origin.SIDELOADED, paths, metadata)
        return EngineMatch(ENGINE_ID, descriptor)
    }

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        unload()
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        val modelPath = requireAssetPath(descriptor, ACOUSTIC_ASSET, engineLabel)
        val tokensPath = requireAssetPath(descriptor, TOKENS_ASSET, engineLabel)
        val lexiconPath = requireAssetPath(descriptor, LEXICON_ASSET, engineLabel)

        val symbolToId = MeloTokens.parse(fileReader(tokensPath))
        val lexicon = MeloLexicon.parse(fileReader(lexiconPath))

        openWithRollback { opened ->
            val acoustic = runtime.createSession(modelPath).also(opened::add)
            acousticSession = acoustic
            frontend = MeloFrontend(symbolToId, lexicon)
            loadedDescriptor = descriptor
        }
    }

    override fun unload() {
        closeAllQuietly(acousticSession)
        acousticSession = null
        frontend = null
        loadedDescriptor = null
    }

    override fun voices(): List<Voice> = loadedDescriptor?.voices ?: emptyList()

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val speed = params.speed
        require(speed > 0f) { "$engineLabel: speed must be positive, was $speed" }
        val session = checkNotNull(acousticSession) { "$engineLabel.synthesizeSentence called before load()" }
        val activeFrontend = checkNotNull(frontend) { "$engineLabel.synthesizeSentence called before load()" }
        val voiceIndex = requireVoiceIndex(voices(), voiceId, engineLabel)
        val language = voices()[voiceIndex].language

        val input = activeFrontend.toModelInput(sentence, language)
        // Speed ALWAYS routes to the model's native length_scale (spec rule 2) — never resample
        // output audio. length_scale is INVERSE to speed, exactly like Piper's scales[1].
        val lengthScale = 1f / speed
        val outputs = session.run(sessionInputs(input, voiceIndex, lengthScale))
        return outputs.singleFloatsOrError(engineLabel)
    }

    /** The real 7-input acoustic contract (see class KDoc), read positionally on the way out. */
    private fun sessionInputs(
        input: ModelInput,
        sid: Int,
        lengthScale: Float,
    ): Map<String, Tensor> {
        val tokenCount = input.tokenIds.size
        val shape = intArrayOf(1, tokenCount)
        // Type-safe accessor (issue #18 item 2) -- no raw `as?` cast at this callsite.
        val tones = input.requireExtra(MeloFrontend.TONES_KEY, engineLabel)
        return mapOf(
            INPUT_X to Tensor.longs(input.tokenIds, shape),
            INPUT_X_LENGTHS to Tensor.longs(longArrayOf(tokenCount.toLong())),
            INPUT_TONES to Tensor.longs(tones, shape),
            INPUT_SID to Tensor.longs(longArrayOf(sid.toLong())),
            INPUT_NOISE_SCALE to Tensor.scalarFloat(DEFAULT_NOISE_SCALE),
            INPUT_LENGTH_SCALE to Tensor.scalarFloat(lengthScale),
            INPUT_NOISE_SCALE_W to Tensor.scalarFloat(DEFAULT_NOISE_SCALE_W),
        )
    }

    private fun assetPaths(
        rootPath: String,
        modelFile: String,
    ): Map<String, String> =
        mapOf(
            ACOUSTIC_ASSET to joinAssetPath(rootPath, modelFile),
            TOKENS_ASSET to joinAssetPath(rootPath, TOKENS_FILE),
            LEXICON_ASSET to joinAssetPath(rootPath, LEXICON_FILE),
            METADATA_ASSET to joinAssetPath(rootPath, METADATA_FILE),
        )

    private fun buildDescriptor(
        modelId: String,
        voices: List<Voice>,
        origin: Origin,
        assetPaths: Map<String, String>,
        metadata: MeloMetadata.Parsed?,
    ): ModelDescriptor =
        ModelDescriptor(
            modelId = modelId,
            engineId = ENGINE_ID,
            displayName = DISPLAY_NAME,
            origin = origin,
            sampleRate = metadata?.sampleRate ?: DEFAULT_SAMPLE_RATE,
            voices = voices,
            defaultVoiceId = defaultVoiceId(voices, metadata),
            // Introspected: MeloTTS's VITS2 graph has a native length_scale input, so it advertises a
            // speed knob (routed to length_scale = 1/speed — never resampled, CLAUDE.md rule 2).
            parameters = listOf(ModelParameter.speed(SPEED_RANGE, DEFAULT_SPEED)),
            assetPaths = assetPaths,
            // Approximate peak-RAM estimate (issue #38): a VITS2 acoustic model plus a BERT frontend
            // session. A-priori only — refined by observed peak RAM.
            resourceCost = ResourceCost.peakRamMebibytes(PEAK_RAM_MIB),
        )

    private fun defaultVoiceId(
        voices: List<Voice>,
        metadata: MeloMetadata.Parsed?,
    ): String {
        val fromMetadata = metadata?.speakerId?.toString()
        return voices.firstOrNull { it.id == fromMetadata }?.id ?: voices.first().id
    }

    /** Builds the n_speakers-driven voice list (spec: ids `"0".."n-1"`, names `"Speaker 1"...`). */
    private fun buildVoices(metadata: MeloMetadata.Parsed): List<Voice> {
        val language = metadata.languageCode ?: DEFAULT_LANGUAGE
        val count = metadata.nSpeakers?.takeIf { it > 0 } ?: 1
        return (0 until count).map { index ->
            Voice(id = index.toString(), name = "Speaker ${index + 1}", language = language)
        }
    }

    companion object {
        const val ENGINE_ID = "melotts"
        const val RUNTIME_ID = "onnx"

        const val ACOUSTIC_ASSET = "acoustic"
        const val TOKENS_ASSET = "tokens"
        const val LEXICON_ASSET = "lexicon"
        const val METADATA_ASSET = "metadata"

        private const val DISPLAY_NAME = "MeloTTS"

        // Family default when a sideloaded bundle ships no metadata.json (SSOT: this is the
        // sanctioned descriptor-building layer, same pattern as KittenEngine.SAMPLE_RATE). When
        // metadata IS present, its own "sample_rate" always wins.
        private const val DEFAULT_SAMPLE_RATE = 44_100

        // Approximate peak resident RAM (MiB) while loaded + generating — acoustic + BERT sessions.
        private const val PEAK_RAM_MIB = 300L
        private val SPEED_RANGE = 0.5f..2.0f
        private const val DEFAULT_SPEED = 1.0f
        private const val DEFAULT_VOICE_ID = "0"
        private const val DEFAULT_VOICE_NAME = "Speaker 1"
        private const val DEFAULT_LANGUAGE = "en"

        private const val MODEL_SUFFIX = ".onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val LEXICON_FILE = "lexicon.txt"
        private const val METADATA_FILE = "metadata.json"

        // PROVEN acoustic tensor contract (scripts/model-verify/run_melo2.py) — no bert, no
        // ja_bert, no language input, no sdp_ratio.
        private const val INPUT_X = "x"
        private const val INPUT_X_LENGTHS = "x_lengths"
        private const val INPUT_TONES = "tones"
        private const val INPUT_SID = "sid"
        private const val INPUT_NOISE_SCALE = "noise_scale"
        private const val INPUT_LENGTH_SCALE = "length_scale"
        private const val INPUT_NOISE_SCALE_W = "noise_scale_w"

        // Default VITS2 inference scales, matching the proven reference recipe exactly.
        private const val DEFAULT_NOISE_SCALE = 0.6f
        private const val DEFAULT_NOISE_SCALE_W = 0.8f
    }
}
