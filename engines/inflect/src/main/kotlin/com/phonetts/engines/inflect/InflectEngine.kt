package com.phonetts.engines.inflect

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
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
import com.phonetts.engines.common.floatsOrError
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.openWithRollback
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.tensorOrError
import java.util.Random

/**
 * Inflect v2 (`owensong/Inflect-Micro-v2-ONNX` and `owensong/Inflect-Nano-v2-ONNX`) - a compact
 * VITS-family end-to-end text-to-waveform model with one fixed English voice.
 *
 * **The graph contract below was VERIFIED, not assumed** (rule 4): both published exports were
 * downloaded at authoring time, their SHA-256s matched upstream's own `onnx/checksums.sha256`, and
 * their input/output names, dtypes and RANKS were read out of the ONNX protobufs themselves:
 *
 * ```
 * duration.onnx (opset 17)
 *   in   tokens        int64  [1, text_len]
 *   in   lengths       int64  [1]
 *   in   length_scale  float  []            <- rank 0, NOT [1]
 *   out  m_p_exp       float  [1, C, mel_len]
 *   out  logs_p_exp    float  [1, C, mel_len]
 *   out  y_mask        float  [1, 1, mel_len]
 * decode.onnx (opset 17)
 *   in   m_p_exp / logs_p_exp / y_mask  (the three tensors above, passed straight through)
 *   in   zp_noise      float  [1, C, mel_len]   <- Gaussian noise, sampled HOST-side
 *   in   noise_scale   float  []            <- rank 0, NOT [1]
 *   out  waveform      float  [1, 1, wav_len]
 * ```
 *
 * `C` is the config's `inter_channels` (192 Micro / 128 Nano) - never hardcoded here: the noise
 * tensor is shaped from `m_p_exp`'s own runtime shape, so a future size works unchanged.
 *
 * Two knobs are advertised because the graphs genuinely have them (rule 1 - parameters are
 * discovered, not assumed): **speed**, routed to the native `length_scale` as its reciprocal
 * exactly as upstream does (rule 2 - never a resample), and **variation**, routed to the native
 * `noise_scale`. Upstream's third control, `seed`, is deliberately NOT advertised: it is an
 * unbounded integer with no range to declare honestly, so instead the noise for a sentence is
 * derived deterministically from that sentence's own text - the same sentence always sounds the
 * same, which is what a re-read or a re-export needs, and different sentences still get
 * independent noise the way upstream's `seed + index` intends.
 *
 * One fixed voice, English only: the release ships a single synthetic speaker and no reference-audio
 * cloning, so [voices] has exactly one entry and [ModelDescriptor.supportsVoiceBlend] stays false
 * (there is no continuous speaker vector to interpolate).
 */
internal class InflectEngine(
    context: EngineContext,
    // Injected so the stochastic latent sampling is deterministic in tests, mirroring
    // SupertonicEngine's noise seam; production draws real Gaussian noise from the sentence seed.
    private val noiseSource: (seed: Long, size: Int) -> FloatArray = ::gaussianNoise,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = DISPLAY_NAME

    private val frontend = InflectFrontend(context.phonemizer)
    private var state: LoadedState? = null

    private class LoadedState(
        val descriptor: ModelDescriptor,
        val duration: InferenceSession,
        val decode: InferenceSession,
    )

    /** The two graph files this bundle was found to carry, at whatever paths they actually live. */
    private class Graphs(
        val durationFile: String,
        val decodeFile: String,
    )

    override fun isLoaded(): Boolean = state != null

    /**
     * Fails closed (rule 4): a bundle is Inflect's only if it carries BOTH graphs and a
     * `config.json` that declares Inflect v2's own [InflectConfig.FORMAT_MARKER]. Two `.onnx` files
     * alone are not enough - `duration.onnx`/`decode.onnx` are generic names another VITS export
     * could reuse - and a config that parses but does not carry the marker is refused rather than
     * guessed at.
     */
    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val graphs = findGraphs(bundle) ?: return null
        val configText = findFile(bundle, CONFIG_FILE)?.let(bundle::sideFile) ?: return null
        val config = InflectConfig.parse(configText) ?: return null
        return EngineMatch(id, buildDescriptor(bundle, graphs, config.sampleRate, config.variant, Origin.BUILT_IN))
    }

    /**
     * The user's manual assignment is authoritative, so this never refuses a bundle that is
     * structurally runnable - but a bundle missing either graph IS structurally unusable (the
     * pipeline is both halves), so that is rejected as a bad argument. Without a readable config
     * the descriptor falls back to the family's published facts rather than inventing new ones.
     */
    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val graphs = findGraphs(bundle)
        requireNotNull(graphs) {
            "bundle '${bundle.id}' is missing $DURATION_FILE and/or $DECODE_FILE; " +
                "$DISPLAY_NAME needs both graphs to synthesize"
        }
        val config = findFile(bundle, CONFIG_FILE)?.let(bundle::sideFile)?.let(InflectConfig::parse)
        return EngineMatch(
            id,
            buildDescriptor(
                bundle,
                graphs,
                config?.sampleRate ?: FAMILY_SAMPLE_RATE,
                config?.variant ?: InflectVariant.UNKNOWN,
                Origin.SIDELOADED,
            ),
        )
    }

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        unload()
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        state =
            openWithRollback { opened ->
                val durationPath = requireAssetPath(descriptor, DURATION_ASSET, engineLabel)
                val decodePath = requireAssetPath(descriptor, DECODE_ASSET, engineLabel)
                LoadedState(
                    descriptor = descriptor,
                    duration = runtime.createSession(durationPath).also(opened::add),
                    decode = runtime.createSession(decodePath).also(opened::add),
                )
            }
    }

    override fun unload() {
        state?.let { closeAllQuietly(it.duration, it.decode) }
        state = null
    }

    override fun voices(): List<Voice> = state?.descriptor?.voices ?: emptyList()

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val loaded = state ?: error("$engineLabel.synthesizeSentence called before load()")
        require(loaded.descriptor.voices.any { it.id == voiceId }) {
            "unknown $DISPLAY_NAME voice id '$voiceId'; this model ships one fixed voice ('$VOICE_ID')"
        }
        val tokens = frontend.toModelInput(sentence, LANGUAGE).tokenIds
        // Nothing in this chunk survived the phoneme table (e.g. a lone unsupported symbol):
        // emit silence rather than feeding the graph an empty token sequence.
        if (tokens.isEmpty()) return FloatArray(0)

        val aligned = runDuration(loaded, tokens, params.speed)
        val waveform = runDecode(loaded, aligned, params, sentence)
        return InflectAudioShaping.shape(waveform, loaded.descriptor.sampleRate, sentence)
    }

    /**
     * Graph 1: tokens -> the aligned acoustic distribution. Speed routes to the model's OWN native
     * duration knob, `length_scale`, as its reciprocal (upstream: `length_scale = 1.0 / speed`) -
     * the audio is never resampled to change speed (rule 2).
     */
    private fun runDuration(
        loaded: LoadedState,
        tokens: LongArray,
        speed: Float,
    ): Map<String, Tensor> =
        loaded.duration.run(
            mapOf(
                TOKENS_KEY to Tensor.longs(tokens, intArrayOf(1, tokens.size)),
                LENGTHS_KEY to Tensor.longs(longArrayOf(tokens.size.toLong()), intArrayOf(1)),
                // rank 0, not [1] - the graph declares a bare scalar and ONNX Runtime enforces it.
                LENGTH_SCALE_KEY to Tensor.rank0Float(1f / speed),
            ),
        )

    /**
     * Graph 2: the aligned distribution + host-sampled Gaussian noise -> the waveform. The three
     * duration outputs are passed straight through untouched; only `zp_noise` (shaped from
     * `m_p_exp`'s own runtime shape) and `noise_scale` are new.
     */
    private fun runDecode(
        loaded: LoadedState,
        aligned: Map<String, Tensor>,
        params: SynthesisParams,
        sentence: String,
    ): FloatArray {
        val meanP = aligned.tensorOrError(M_P_KEY, engineLabel)
        val variation = params.value(VARIATION_ID, DEFAULT_VARIATION)
        require(variation in VARIATION_RANGE) {
            "$engineLabel: variation $variation is outside the supported range $VARIATION_RANGE"
        }
        val noise = noiseSource(sentenceSeed(sentence), meanP.asFloats().size)
        val outputs =
            loaded.decode.run(
                mapOf(
                    M_P_KEY to meanP,
                    LOGS_P_KEY to aligned.tensorOrError(LOGS_P_KEY, engineLabel),
                    Y_MASK_KEY to aligned.tensorOrError(Y_MASK_KEY, engineLabel),
                    ZP_NOISE_KEY to Tensor.floats(noise, meanP.shape),
                    NOISE_SCALE_KEY to Tensor.rank0Float(variation),
                ),
            )
        return outputs.floatsOrError(WAVEFORM_KEY, engineLabel)
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        graphs: Graphs,
        sampleRate: Int,
        variant: InflectVariant,
        origin: Origin,
    ): ModelDescriptor {
        val voice = Voice(id = VOICE_ID, name = VOICE_NAME, language = LANGUAGE)
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = variant.displayName,
            origin = origin,
            // Read from the model's own config.json (SSOT, rule 1) - not a literal typed here.
            sampleRate = sampleRate,
            voices = listOf(voice),
            defaultVoiceId = voice.id,
            // Both knobs correspond to a real graph input verified above; nothing is advertised
            // that the model cannot actually honour.
            parameters = listOf(ModelParameter.speed(SPEED_RANGE, DEFAULT_SPEED), VARIATION_PARAMETER),
            assetPaths =
                mapOf(
                    DURATION_ASSET to joinAssetPath(bundle, graphs.durationFile),
                    DECODE_ASSET to joinAssetPath(bundle, graphs.decodeFile),
                ),
            resourceCost = ResourceCost.peakRamMebibytes(variant.peakRamMebibytes),
        )
    }

    /** Both graphs, or null if either is absent. */
    private fun findGraphs(bundle: ModelBundle): Graphs? {
        val duration = findFile(bundle, DURATION_FILE) ?: return null
        val decode = findFile(bundle, DECODE_FILE) ?: return null
        return Graphs(duration, decode)
    }

    /**
     * The bundle entry for [name], accepting either a flat folder (what the in-app download
     * produces, since a curated file lands under its local name) or the published repo layout
     * where the graphs sit in an `onnx/` subdirectory (what a `git clone` sideload produces).
     */
    private fun findFile(
        bundle: ModelBundle,
        name: String,
    ): String? = bundle.fileNames.firstOrNull { it == name || it.endsWith("/$name") }

    internal companion object {
        const val ENGINE_ID = "inflect"
        const val DISPLAY_NAME = "Inflect"

        /** The [com.phonetts.core.runtime.Runtime] id: both graphs are plain ONNX, opset 17. */
        const val RUNTIME_ID = "onnx"

        const val LANGUAGE = "en"

        /**
         * The single fixed synthetic speaker the release ships. Not a real person's voice and not
         * clonable from reference audio - one voice is the honest list, not a placeholder.
         */
        const val VOICE_ID = "default"
        const val VOICE_NAME = "Default (male)"

        // Published file names, verified present in both ONNX repos.
        const val DURATION_FILE = "duration.onnx"
        const val DECODE_FILE = "decode.onnx"
        const val CONFIG_FILE = "config.json"

        const val DURATION_ASSET = "duration"
        const val DECODE_ASSET = "decode"

        // VERIFIED graph tensor names (read from the ONNX protobufs - see the class KDoc).
        const val TOKENS_KEY = "tokens"
        const val LENGTHS_KEY = "lengths"
        const val LENGTH_SCALE_KEY = "length_scale"
        const val M_P_KEY = "m_p_exp"
        const val LOGS_P_KEY = "logs_p_exp"
        const val Y_MASK_KEY = "y_mask"
        const val ZP_NOISE_KEY = "zp_noise"
        const val NOISE_SCALE_KEY = "noise_scale"
        const val WAVEFORM_KEY = "waveform"

        /** Upstream's documented public range for `speed` (`0.5-2.0`, default `1.0`). */
        val SPEED_RANGE = 0.5f..2.0f
        const val DEFAULT_SPEED = 1.0f

        /** Upstream's documented public range for `variation` (`0.0-1.0`, default `0.667`). */
        const val VARIATION_ID = "variation"
        val VARIATION_RANGE = 0.0f..1.0f
        const val DEFAULT_VARIATION = 0.667f

        /**
         * The second knob the UI renders with no app change (rule 1): the native `noise_scale`
         * input. Lower is steadier, higher samples more latent variation.
         */
        val VARIATION_PARAMETER =
            ModelParameter(
                id = VARIATION_ID,
                displayName = "Variation",
                kind = ModelParameter.Kind.CONTINUOUS,
                range = VARIATION_RANGE,
                default = DEFAULT_VARIATION,
            )

        /**
         * Used ONLY by [forcedMatch] when a sideloaded bundle has no readable `config.json`. 24 kHz
         * is what both published exports declare (`data.sampling_rate`, read from the real files);
         * auto-detected bundles always read their own value instead of this one.
         */
        const val FAMILY_SAMPLE_RATE = 24_000

        /**
         * The per-sentence noise seed. Upstream uses `seed + chunk_index`, which makes the same
         * sentence sound different depending on where it lands in a document; deriving the seed
         * from the sentence text instead keeps a re-read or a re-export of that sentence identical
         * while still decorrelating different sentences.
         */
        fun sentenceSeed(sentence: String): Long = sentence.hashCode().toLong()
    }
}

/** Standard-normal noise for `zp_noise`, reproducible for a given [seed]. */
private fun gaussianNoise(
    seed: Long,
    size: Int,
): FloatArray {
    val random = Random(seed)
    return FloatArray(size) { random.nextGaussian().toFloat() }
}
