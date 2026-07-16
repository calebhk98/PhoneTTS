package com.phonetts.engines.melotts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.closeAllQuietly
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.openWithRollback
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.requireVoiceIndex
import com.phonetts.engines.common.singleFloatsOrError

/**
 * MeloTTS engine (spec Phase 2.2). Unlike the phoneme-only engines, MeloTTS's frontend needs a
 * learned BERT prosody pass before the acoustic model runs, so this engine holds TWO
 * [InferenceSession]s — BERT and the VITS2 acoustic model — both created from
 * `descriptor.assetPaths` off the same "onnx" [com.phonetts.core.runtime.Runtime] (spec §5.3).
 *
 * Verified facts (docs/research/model-facts.md): 44100 Hz sample rate, MIT license, multilingual.
 * The acoustic graph's real 11-input contract (`x`/`x_lengths`/`sid`/`tone`/`language`/`bert`/
 * `ja_bert`/`noise_scale`/`length_scale`/`noise_scale_w`/`sdp_ratio`, auto-numbered output) is
 * validated in docs/research/onnx-io.md — the engine assembles all 11 named inputs and reads the
 * single output positionally, since the export doesn't name it predictably. Speed routes to
 * `length_scale`, inversely (spec rule 2), exactly like Piper's `scales[1]`.
 */
internal class MeloEngine(
    context: EngineContext,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private var bertSession: InferenceSession? = null
    private var acousticSession: InferenceSession? = null
    private var frontend: MeloFrontend? = null
    private var loadedDescriptor: ModelDescriptor? = null

    override fun isLoaded(): Boolean = acousticSession != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        if (!hasMeloCompanionFiles(bundle)) return null
        val rootPath = bundle.rootPath ?: return null
        val config = bundle.sideFile(CONFIG_FILE) ?: return null
        val voices = parseSpeakers(config)?.takeIf { it.isNotEmpty() } ?: return null

        val descriptor = buildDescriptor(bundle.id, voices, Origin.BUILT_IN, assetPaths(rootPath))
        return EngineMatch(ENGINE_ID, descriptor)
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        require(bundle.hasFile(ACOUSTIC_FILE)) {
            "bundle ${bundle.id} has no MeloTTS acoustic weights ($ACOUSTIC_FILE)"
        }
        val rootPath =
            requireNotNull(bundle.rootPath) {
                "bundle ${bundle.id} has no root path to build MeloTTS asset paths from"
            }
        val voices =
            bundle.sideFile(CONFIG_FILE)?.let(::parseSpeakers)?.takeIf { it.isNotEmpty() }
                ?: listOf(Voice(DEFAULT_VOICE_ID, DEFAULT_VOICE_NAME, DEFAULT_LANGUAGE))

        val descriptor = buildDescriptor(bundle.id, voices, Origin.SIDELOADED, assetPaths(rootPath))
        return EngineMatch(ENGINE_ID, descriptor)
    }

    override suspend fun load(descriptor: ModelDescriptor) {
        unload()
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        val bertPath = requireAssetPath(descriptor, BERT_ASSET, engineLabel)
        val acousticPath = requireAssetPath(descriptor, ACOUSTIC_ASSET, engineLabel)

        openWithRollback { opened ->
            val bert = runtime.createSession(bertPath).also(opened::add)
            val acoustic = runtime.createSession(acousticPath).also(opened::add)
            bertSession = bert
            acousticSession = acoustic
            frontend = MeloFrontend(bert, context.phonemizer)
            loadedDescriptor = descriptor
        }
    }

    override fun unload() {
        closeAllQuietly(acousticSession, bertSession)
        acousticSession = null
        bertSession = null
        frontend = null
        loadedDescriptor = null
    }

    override fun voices(): List<Voice> = loadedDescriptor?.voices ?: emptyList()

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        speed: Float,
    ): FloatArray {
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

    /** All 11 acoustic-model inputs, named per the validated real contract (docs/research/onnx-io.md). */
    private fun sessionInputs(
        input: ModelInput,
        voiceIndex: Int,
        lengthScale: Float,
    ): Map<String, Tensor> {
        val tokenCount = input.tokenIds.size
        val shape = intArrayOf(1, tokenCount)
        return mapOf(
            ACOUSTIC_INPUT_X to Tensor.longs(input.tokenIds, shape),
            ACOUSTIC_INPUT_X_LENGTHS to Tensor.longs(longArrayOf(tokenCount.toLong())),
            ACOUSTIC_INPUT_SID to Tensor.longs(longArrayOf(voiceIndex.toLong())),
            ACOUSTIC_INPUT_TONE to Tensor.longs(extraLongs(input, MeloFrontend.EXTRA_TONE), shape),
            ACOUSTIC_INPUT_LANGUAGE to Tensor.longs(extraLongs(input, MeloFrontend.EXTRA_LANGUAGE), shape),
            ACOUSTIC_INPUT_BERT to extraTensor(input, MeloFrontend.EXTRA_BERT),
            ACOUSTIC_INPUT_JA_BERT to extraTensor(input, MeloFrontend.EXTRA_JA_BERT),
            ACOUSTIC_INPUT_NOISE_SCALE to Tensor.scalarFloat(DEFAULT_NOISE_SCALE),
            ACOUSTIC_INPUT_LENGTH_SCALE to Tensor.scalarFloat(lengthScale),
            ACOUSTIC_INPUT_NOISE_SCALE_W to Tensor.scalarFloat(DEFAULT_NOISE_SCALE_W),
            ACOUSTIC_INPUT_SDP_RATIO to Tensor.scalarFloat(DEFAULT_SDP_RATIO),
        )
    }

    private fun extraLongs(
        input: ModelInput,
        key: String,
    ): LongArray = input.extras[key] as? LongArray ?: error("$engineLabel: frontend did not produce '$key'")

    private fun extraTensor(
        input: ModelInput,
        key: String,
    ): Tensor = input.extras[key] as? Tensor ?: error("$engineLabel: frontend did not produce '$key'")

    private fun hasMeloCompanionFiles(bundle: ModelBundle): Boolean =
        bundle.hasFile(ACOUSTIC_FILE) && bundle.hasFile(BERT_FILE) && bundle.hasFile(TOKENIZER_FILE)

    private fun assetPaths(rootPath: String): Map<String, String> =
        mapOf(
            ACOUSTIC_ASSET to joinAssetPath(rootPath, ACOUSTIC_FILE),
            BERT_ASSET to joinAssetPath(rootPath, BERT_FILE),
            TOKENIZER_ASSET to joinAssetPath(rootPath, TOKENIZER_FILE),
            CONFIG_ASSET to joinAssetPath(rootPath, CONFIG_FILE),
        )

    private fun buildDescriptor(
        modelId: String,
        voices: List<Voice>,
        origin: Origin,
        assetPaths: Map<String, String>,
    ): ModelDescriptor =
        ModelDescriptor(
            modelId = modelId,
            engineId = ENGINE_ID,
            displayName = DISPLAY_NAME,
            origin = origin,
            sampleRate = SAMPLE_RATE,
            voices = voices,
            speedRange = SPEED_RANGE,
            defaultVoiceId = voices.first().id,
            defaultSpeed = DEFAULT_SPEED,
            assetPaths = assetPaths,
        )

    /**
     * Extracts MeloTTS's `spk2id`-style speaker table and language tag out of the small
     * `config.json` side file, e.g. `{"data": {"spk2id": {"EN-US": 0, "EN-BR": 1},
     * "language": "EN"}}`. Fails closed (returns null) if no `spk2id` object is found, since a
     * MeloTTS config without a speaker table cannot be trusted (spec §9.1).
     */
    private fun parseSpeakers(config: String): List<Voice>? {
        val spk2idIndex = config.indexOf(SPK2ID_KEY)
        if (spk2idIndex < 0) return null
        val braceStart = config.indexOf('{', spk2idIndex)
        val braceEnd = config.indexOf('}', braceStart.coerceAtLeast(0))
        if (braceStart < 0 || braceEnd < 0) return null

        val body = config.substring(braceStart + 1, braceEnd)
        val language = LANGUAGE_REGEX.find(config)?.groupValues?.get(1) ?: DEFAULT_LANGUAGE
        val voices =
            SPEAKER_ENTRY_REGEX.findAll(body)
                .map { match -> Voice(id = match.groupValues[1], name = match.groupValues[1], language = language) }
                .toList()
        return voices.ifEmpty { null }
    }

    companion object {
        const val ENGINE_ID = "melotts"
        const val RUNTIME_ID = "onnx"

        const val BERT_ASSET = "bert"
        const val ACOUSTIC_ASSET = "acoustic"
        const val TOKENIZER_ASSET = "tokenizer"
        const val CONFIG_ASSET = "config"

        private const val DISPLAY_NAME = "MeloTTS"
        private const val SAMPLE_RATE = 44_100
        private val SPEED_RANGE = 0.5f..2.0f
        private const val DEFAULT_SPEED = 1.0f
        private const val DEFAULT_VOICE_ID = "EN-Default"
        private const val DEFAULT_VOICE_NAME = "Default"
        private const val DEFAULT_LANGUAGE = "EN"

        private const val ACOUSTIC_FILE = "model.onnx"
        private const val BERT_FILE = "bert_model.onnx"
        private const val TOKENIZER_FILE = "tokenizer.json"
        private const val CONFIG_FILE = "config.json"

        // VALIDATED real acoustic tensor input names (seasonstudio/melotts_zh_mix_en_onnx — see
        // docs/research/onnx-io.md). The output is auto-numbered by the export, so it is read
        // positionally via singleFloatsOrError instead of by a hardcoded name.
        private const val ACOUSTIC_INPUT_X = "x"
        private const val ACOUSTIC_INPUT_X_LENGTHS = "x_lengths"
        private const val ACOUSTIC_INPUT_SID = "sid"
        private const val ACOUSTIC_INPUT_TONE = "tone"
        private const val ACOUSTIC_INPUT_LANGUAGE = "language"
        private const val ACOUSTIC_INPUT_BERT = "bert"
        private const val ACOUSTIC_INPUT_JA_BERT = "ja_bert"
        private const val ACOUSTIC_INPUT_NOISE_SCALE = "noise_scale"
        private const val ACOUSTIC_INPUT_LENGTH_SCALE = "length_scale"
        private const val ACOUSTIC_INPUT_NOISE_SCALE_W = "noise_scale_w"
        private const val ACOUSTIC_INPUT_SDP_RATIO = "sdp_ratio"

        // Default VITS2/SDP inference scales. Not user-tunable (no descriptor field claims them),
        // so these are fixed to `TTS.tts_to_file`'s own defaults upstream (melo/api.py):
        // noise_scale (flow/posterior sampling noise), noise_scale_w (stochastic duration
        // predictor noise), sdp_ratio (blend between the stochastic and deterministic duration
        // predictors — 0 = fully deterministic, 1 = fully stochastic).
        private const val DEFAULT_NOISE_SCALE = 0.6f
        private const val DEFAULT_NOISE_SCALE_W = 0.8f
        private const val DEFAULT_SDP_RATIO = 0.2f

        private const val SPK2ID_KEY = "spk2id"
        private val SPEAKER_ENTRY_REGEX = Regex("\"([A-Za-z0-9_\\-]+)\"\\s*:\\s*\\d+")
        private val LANGUAGE_REGEX = Regex("\"language\"\\s*:\\s*\"([A-Za-z\\-]+)\"")
    }
}
