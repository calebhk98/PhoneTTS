package com.phonetts.engines.melotts

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
import com.phonetts.engines.common.openWithRollback
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.requireVoiceIndex

/**
 * MeloTTS engine (spec Phase 2.2). Unlike the phoneme-only engines, MeloTTS's frontend needs a
 * learned BERT prosody pass before the acoustic model runs, so this engine holds TWO
 * [InferenceSession]s — BERT and the VITS2 acoustic model — both created from
 * `descriptor.assetPaths` off the same "onnx" [com.phonetts.core.runtime.Runtime] (spec §5.3).
 *
 * Verified facts (docs/research/model-facts.md): 44100 Hz sample rate, MIT license,
 * multilingual, native speed parameter `speed`.
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
            frontend = MeloFrontend(bert)
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
        val session = checkNotNull(acousticSession) { "$engineLabel.synthesizeSentence called before load()" }
        val activeFrontend = checkNotNull(frontend) { "$engineLabel.synthesizeSentence called before load()" }
        val voiceIndex = requireVoiceIndex(voices(), voiceId, engineLabel)
        val language = voices()[voiceIndex].language

        val input = activeFrontend.toModelInput(sentence, language)
        val bertFeatures =
            input.extras[MeloFrontend.EXTRA_BERT_FEATURES] as? Tensor
                ?: error("frontend did not produce '${MeloFrontend.EXTRA_BERT_FEATURES}'")
        val outputs =
            session.run(
                mapOf(
                    // Assumed real MeloTTS VITS2 acoustic-model export input names.
                    ACOUSTIC_INPUT_TOKENS to Tensor.longs(input.tokenIds, intArrayOf(1, input.tokenIds.size)),
                    ACOUSTIC_INPUT_BERT to bertFeatures,
                    ACOUSTIC_INPUT_SPEAKER to Tensor.longs(longArrayOf(voiceIndex.toLong())),
                    // Native speed knob (spec rule #2): routed straight through, never resampled.
                    ACOUSTIC_INPUT_SPEED to Tensor.scalarFloat(speed),
                ),
            )
        return outputs.floatsOrError(ACOUSTIC_OUTPUT_AUDIO, engineLabel)
    }

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

        private const val ACOUSTIC_INPUT_TOKENS = "token_ids"
        private const val ACOUSTIC_INPUT_BERT = "bert_features"
        private const val ACOUSTIC_INPUT_SPEAKER = "speaker_id"
        private const val ACOUSTIC_INPUT_SPEED = "speed"
        private const val ACOUSTIC_OUTPUT_AUDIO = "audio"

        private const val SPK2ID_KEY = "spk2id"
        private val SPEAKER_ENTRY_REGEX = Regex("\"([A-Za-z0-9_\\-]+)\"\\s*:\\s*\\d+")
        private val LANGUAGE_REGEX = Regex("\"language\"\\s*:\\s*\"([A-Za-z\\-]+)\"")
    }
}
