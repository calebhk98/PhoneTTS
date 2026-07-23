package com.phonetts.engines.litert

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.closeAllQuietly
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime

/**
 * The LiteRT / `.tflite` engine (issue #109) - a deliberately CONSERVATIVE, FAIL-CLOSED scaffold.
 *
 * Its whole job right now is to make sure a downloaded `.tflite` bundle actually REACHES the
 * `"litert"` runtime, which - on [doLoad] - writes the model's I/O signature to the durable error log
 * so we can learn each architecture's contract. It does NOT yet produce audio: the per-architecture
 * input/output wiring is unknown, so [synthesizeSentence] throws a clear, user-facing error pointing
 * at that logged signature instead of guessing (CLAUDE.md rule 4's spirit - refusing rather than
 * faking is a feature).
 *
 * SSOT (rule 1): the sample rate is READ from `config.json` when present, else a clearly-flagged
 * scaffold default; no model name is hardcoded (the display name is derived from the bundle id). The
 * engine declares no tunable parameters, so [ModelDescriptor.speedRange] locks to `1.0..1.0`.
 */
internal class LiteRtEngine(
    context: EngineContext,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = displayName

    private var session: InferenceSession? = null
    private var loadedDescriptor: ModelDescriptor? = null

    override fun isLoaded(): Boolean = session != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val weightsFile = selectWeightsFile(bundle) ?: return null
        // Fail closed and stay out of every other engine's lane: an ONNX bundle belongs to an ONNX
        // engine, so a bundle that ALSO ships .onnx is not ours.
        if (bundle.hasFileEndingWith(ONNX_SUFFIX)) return null
        // Conservative: a bare .tflite with no metadata drops to the user-pick fallback; we only claim
        // one that also carries a recognisable companion (config.json or tokens.txt).
        val hasCompanion = bundle.hasFile(CONFIG_FILE) || bundle.hasFile(TOKENS_FILE)
        if (!hasCompanion) return null
        val descriptor = buildDescriptor(bundle, weightsFile, bundle.sideFile(CONFIG_FILE), Origin.BUILT_IN)
        return EngineMatch(id, descriptor)
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val weightsFile =
            selectWeightsFile(bundle)
                ?: throw IllegalArgumentException(
                    "LiteRT forcedMatch requires at least one .tflite file in bundle '${bundle.id}'",
                )
        val descriptor = buildDescriptor(bundle, weightsFile, bundle.sideFile(CONFIG_FILE), Origin.SIDELOADED)
        return EngineMatch(id, descriptor)
    }

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, LITERT_RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        unload()
        val runtime = requireRuntime(context, LITERT_RUNTIME_ID, engineLabel)
        val weightsPath = requireAssetPath(descriptor, WEIGHTS_ASSET, engineLabel)
        // createSession logs the model's full I/O signature to the durable error log (the useful
        // artifact for this scaffold) - or, on failure, a rich load diagnostic - before returning.
        session = runtime.createSession(weightsPath)
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
        params: SynthesisParams,
    ): FloatArray =
        // The model loaded (isLoaded() is guarded by the base class) and its I/O signature is now in
        // the error log; per-architecture synthesis wiring is intentionally not done yet (issue #109),
        // so refuse clearly rather than emit noise.
        error(
            "LiteRT model recognized; its input/output signature has been written to the error log, " +
                "but this architecture is not wired for synthesis yet.",
        )

    /** The one `.tflite` weights file this bundle loads (smallest name deterministically), or null. */
    private fun selectWeightsFile(bundle: ModelBundle): String? {
        val tfliteFiles = bundle.fileNames.filter { it.endsWith(TFLITE_SUFFIX) }
        if (tfliteFiles.isEmpty()) return null
        return tfliteFiles.sorted().first()
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        weightsFile: String,
        configJson: String?,
        origin: Origin,
    ): ModelDescriptor {
        val sampleRate = parseSampleRate(configJson) ?: FALLBACK_SAMPLE_RATE
        val voice = Voice(id = DEFAULT_VOICE_ID, name = DISPLAY_NAME, language = DEFAULT_LANGUAGE)
        val assetPaths = mapOf(WEIGHTS_ASSET to joinAssetPath(bundle, weightsFile))
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = "$DISPLAY_NAME - ${bundle.id}",
            origin = origin,
            sampleRate = sampleRate,
            voices = listOf(voice),
            defaultVoiceId = voice.id,
            // No tunable knob discovered (scaffold): locks speedRange to 1.0..1.0 (rule 2).
            parameters = emptyList(),
            assetPaths = assetPaths,
        )
    }

    // Best-effort: read config.json's sample rate under either common key, else null so the caller
    // falls back to the flagged scaffold default (SSOT - never a literal outside this layer).
    private fun parseSampleRate(configJson: String?): Int? {
        val root = configJson?.let(MiniJson::parse)?.asObjectOrNull() ?: return null
        val rate = root[KEY_SAMPLE_RATE]?.asIntOrNull() ?: root[KEY_SAMPLING_RATE]?.asIntOrNull() ?: return null
        return rate.takeIf { it > 0 }
    }

    companion object {
        const val ENGINE_ID = "litert"
        const val DISPLAY_NAME = "LiteRT"

        private const val LITERT_RUNTIME_ID = "litert"

        private const val TFLITE_SUFFIX = ".tflite"
        private const val ONNX_SUFFIX = ".onnx"

        private const val CONFIG_FILE = "config.json"
        private const val TOKENS_FILE = "tokens.txt"

        private const val WEIGHTS_ASSET = "weights"

        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_SAMPLING_RATE = "sampling_rate"

        private const val DEFAULT_VOICE_ID = "default"
        private const val DEFAULT_LANGUAGE = "und"

        // SCAFFOLD VALUE (issue #109): used only when config.json carries no sample rate. 24 kHz is a
        // common neural-TTS output rate; the real rate must come from the model/config once synthesis
        // is wired. Never used by a bundle whose config.json declares its own rate.
        private const val FALLBACK_SAMPLE_RATE = 24_000
    }
}
