package com.phonetts.engines.supertonic

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
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.openWithRollback
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.sideFileContainsMarker
import com.phonetts.engines.common.singleFloatsOrError
import com.phonetts.engines.common.singleTensorOrError
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Supertonic (Supertone Inc, `Supertone/supertonic-3` on Hugging Face) - a ~99M-parameter,
 * on-device, 31-language ONNX pipeline. Unlike every other engine in this registry it is FOUR
 * chained graphs, not one: `duration_predictor.onnx` -> `text_encoder.onnx` -> a fixed-step
 * `vector_estimator.onnx` flow-matching denoising loop -> `vocoder.onnx`. See
 * `docs/research/supertonic-facts.md` for every fact below's source URL and verification date
 * (2026-07-24); `engines/supertonic/INTEGRATION.md` for parent-owned wiring steps.
 *
 * PIPELINE (VALIDATED against `supertonic-py/supertonic/core.py`'s `Supertonic.__call__` AND the
 * official `supertone-inc/supertonic` Java example's `TextToSpeech._infer` - both official
 * reference implementations agree tensor-name-for-tensor-name):
 *  1. `duration_predictor.onnx({text_ids, style_dp, text_mask}) -> duration` (seconds). Speed
 *     routes HERE, exactly as the reference does: `duration = duration / speed` - a native
 *     duration knob, never a resample of output audio (CLAUDE.md rule 2).
 *  2. `text_encoder.onnx({text_ids, style_ttl, text_mask}) -> text_emb`.
 *  3. A noisy latent of shape `[1, latent_dim * chunk_compress_factor, latent_frames]` is sampled
 *     (Gaussian noise, [noiseSource]) - `latent_frames` derived from `duration` the same way the
 *     reference does (`ceil(duration * sampleRate / (base_chunk_size * chunk_compress_factor))`).
 *     `vector_estimator.onnx({noisy_latent, text_emb, style_ttl, text_mask, latent_mask,
 *     current_step, total_step})` runs [DEFAULT_TOTAL_STEPS] times, each iteration's output latent
 *     feeding the next iteration's `noisy_latent` input (an ODE/flow-matching denoising loop).
 *  4. `vocoder.onnx({latent}) -> wav`, trimmed to `round(duration * sampleRate)` samples (the
 *     vocoder's raw output can be longer than the requested duration - both reference
 *     implementations trim it the same way before use).
 *
 * OUTPUT TENSOR NAMES - **ASSUMPTION, not verified** (the one genuine unknown in this pipeline,
 * called out rather than guessed at, CLAUDE.md's honesty rule): both reference implementations
 * read every graph's output **positionally** (`session.run(None, ...)` destructured as
 * `value, *_ = ...` in Python; `result.get(0)` in Java) rather than by name, so neither ever
 * surfaces what ONNX actually named those outputs. This engine therefore reads each stage's output
 * the same way [com.phonetts.engines.melotts.MeloEngine] already does for its own auto-numbered
 * acoustic graph output - positionally, via [singleTensorOrError]/[singleFloatsOrError] - which
 * additionally assumes each graph has EXACTLY ONE output; if a real graph turns out to report more
 * than one, that assumption needs revisiting against the actual loaded graph.
 *
 * SPEED - native, routed to the duration predictor's output as described above.
 * VALIDATED range/default: `supertonic-py/supertonic/config.py` `MIN_SPEED = 0.7`,
 * `MAX_SPEED = 2.0`, `DEFAULT_SPEED = 1.05`.
 *
 * LANGUAGE - Supertonic's 10 voice styles (`M1`-`M5`, `F1`-`F5`) are each usable across all 31
 * supported languages (voice timbre and language are orthogonal - neither reference implementation
 * ties a style to a language). That is a second genuine, model-native tunable dimension, so - per
 * CLAUDE.md rule 1's "discovered, not assumed" knobs - it is declared as its own CHOICE
 * [ModelParameter] ([LANGUAGE_PARAMETER_ID]) rather than folded into [Voice.language] (which can
 * only name ONE language per voice and would otherwise force an arbitrary pick). [Voice.language]
 * itself is set to [DEFAULT_LANGUAGE] for every discovered voice - a required field, not a claim
 * that the voice IS English-only.
 *
 * STEPS - `total_step`/`current_step` are genuine runtime graph inputs (unlike, say,
 * [com.phonetts.engines.f5tts.F5TtsEngine]'s NFE count, which is baked into the exported graph at
 * conversion time), so a future ticket could reasonably expose a "quality/speed" step-count
 * [ModelParameter]. This one deliberately does not: it is out of the scope this ticket was given
 * (which named only the model's SPEED knob), so [DEFAULT_TOTAL_STEPS] stays an internal constant -
 * `supertonic-py/supertonic/config.py`'s own `DEFAULT_TOTAL_STEPS = 8`.
 *
 * INSPECT - fails closed (spec §9.1) unless a bundle carries all four `.onnx` graphs plus
 * `onnx/tts.json` (whose content must additionally mention [CONFIG_MARKER] - defense in depth on
 * top of the already-distinctive four-onnx-filename fingerprint) plus `onnx/unicode_indexer.json`,
 * AND at least one `voice_styles/<name>.json`. Voices are DISCOVERED by file name, never a
 * hardcoded `M1..F5` list (rule 1) - a bundle shipping only a subset, or a community-added style,
 * is claimed the same way. [forcedMatch] only requires the four `.onnx` graphs (the user's explicit
 * choice is authoritative); a bundle with no `voice_styles/<name>.json` at all falls back to
 * [DEFAULT_VOICE], which has no real style vectors and so fails loudly, not silently, at synthesis
 * time (mirrors [com.phonetts.engines.f5tts.F5TtsEngine]'s reference-clip-less fallback).
 */
internal class SupertonicEngine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable without real files on disk (parity with
    // F5TtsEngine's textReader / KittenEngine's fileReader seams).
    private val textReader: (path: String) -> String = { File(it).readText() },
    // Injected so the flow-matching denoising loop is deterministic in tests (parity with the
    // other injected-seam constructor params above); production samples real Gaussian noise.
    private val noiseSource: (Int) -> FloatArray = ::sampleGaussianNoise,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = DISPLAY_NAME

    private var state: LoadedState? = null

    override fun isLoaded(): Boolean = state != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        if (!REQUIRED_FILES.all { bundle.hasFile(it) }) return null
        if (!bundle.sideFileContainsMarker(CONFIG_FILE, CONFIG_MARKER)) return null
        val voices = discoverVoices(bundle)
        if (voices.isEmpty()) return null
        return EngineMatch(id, buildDescriptor(bundle, voices, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        require(REQUIRED_ONNX_FILES.all { bundle.hasFile(it) }) {
            "$engineLabel forcedMatch requires all four ONNX graphs (${REQUIRED_ONNX_FILES.joinToString()})" +
                " in bundle '${bundle.id}' - the pipeline is unusable with only some of them"
        }
        return EngineMatch(id, buildDescriptor(bundle, discoverVoices(bundle), Origin.SIDELOADED))
    }

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        unload()
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        state =
            openWithRollback { opened ->
                val dpPath = requireAssetPath(descriptor, DP_ASSET, engineLabel)
                val textEncPath = requireAssetPath(descriptor, TEXT_ENCODER_ASSET, engineLabel)
                val vectorEstPath = requireAssetPath(descriptor, VECTOR_ESTIMATOR_ASSET, engineLabel)
                val vocoderPath = requireAssetPath(descriptor, VOCODER_ASSET, engineLabel)

                val dpSession = runtime.createSession(dpPath).also(opened::add)
                val textEncSession = runtime.createSession(textEncPath).also(opened::add)
                val vectorEstSession = runtime.createSession(vectorEstPath).also(opened::add)
                val vocoderSession = runtime.createSession(vocoderPath).also(opened::add)

                val indexerPath = requireAssetPath(descriptor, INDEXER_ASSET, engineLabel)
                val indexer =
                    SupertonicUnicodeIndexer.parse(textReader(indexerPath)) ?: error(
                        "$engineLabel: '$indexerPath' is not a valid unicode_indexer.json" +
                            " (expected a flat ${SupertonicUnicodeIndexer.EXPECTED_SIZE}-entry array)",
                    )

                LoadedState(
                    descriptor = descriptor,
                    dpSession = dpSession,
                    textEncSession = textEncSession,
                    vectorEstSession = vectorEstSession,
                    vocoderSession = vocoderSession,
                    frontend = SupertonicFrontend(indexer),
                    styles = loadStyles(descriptor),
                )
            }
    }

    override fun unload() {
        state?.let { closeAllQuietly(it.dpSession, it.textEncSession, it.vectorEstSession, it.vocoderSession) }
        state = null
    }

    override fun voices(): List<Voice> = state?.descriptor?.voices ?: emptyList()

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val loaded = checkNotNull(state) { "$engineLabel.synthesizeSentence called before load()" }
        val style = loaded.styles[voiceId] ?: error("$engineLabel: voice '$voiceId' has no loaded style vectors")
        val language = resolveLanguage(params)

        val tokenIds = loaded.frontend.toModelInput(sentence, language).tokenIds
        val textIdsTensor = Tensor.longs(tokenIds, intArrayOf(1, tokenIds.size))
        val textMaskTensor = Tensor.floats(FloatArray(tokenIds.size) { 1f }, intArrayOf(1, 1, tokenIds.size))
        val styleDpTensor = Tensor.floats(style.dp, style.dpShape)
        val styleTtlTensor = Tensor.floats(style.ttl, style.ttlShape)

        val durationSeconds = predictDuration(loaded, textIdsTensor, styleDpTensor, textMaskTensor, params.speed)
        val textEmbTensor = encodeText(loaded, textIdsTensor, styleTtlTensor, textMaskTensor)
        val finalLatent = denoise(loaded, textEmbTensor, styleTtlTensor, textMaskTensor, durationSeconds)

        val wav = loaded.vocoderSession.run(mapOf(LATENT_KEY to finalLatent)).singleFloatsOrError(engineLabel)
        val actualSamples = (durationSeconds * SAMPLE_RATE_HZ).toInt().coerceIn(0, wav.size)
        return wav.copyOf(actualSamples)
    }

    private fun predictDuration(
        loaded: LoadedState,
        textIdsTensor: Tensor,
        styleDpTensor: Tensor,
        textMaskTensor: Tensor,
        speed: Float,
    ): Float {
        val outputs =
            loaded.dpSession.run(
                mapOf(TEXT_IDS_KEY to textIdsTensor, STYLE_DP_KEY to styleDpTensor, TEXT_MASK_KEY to textMaskTensor),
            )
        // supertonic-py/supertonic/core.py: `dur_onnx = dur_onnx / speed` - the native duration knob
        // speed routes to (CLAUDE.md rule 2), never a resample of the FINAL waveform.
        return outputs.singleFloatsOrError(engineLabel).first() / speed
    }

    private fun encodeText(
        loaded: LoadedState,
        textIdsTensor: Tensor,
        styleTtlTensor: Tensor,
        textMaskTensor: Tensor,
    ): Tensor =
        loaded.textEncSession
            .run(mapOf(TEXT_IDS_KEY to textIdsTensor, STYLE_TTL_KEY to styleTtlTensor, TEXT_MASK_KEY to textMaskTensor))
            .singleTensorOrError(engineLabel)

    /** The fixed-step ODE/flow-matching denoising loop (see class KDoc step 3). */
    private fun denoise(
        loaded: LoadedState,
        textEmbTensor: Tensor,
        styleTtlTensor: Tensor,
        textMaskTensor: Tensor,
        durationSeconds: Float,
    ): Tensor {
        val latentFrames = latentFrameCount(durationSeconds)
        var noisyLatent =
            Tensor.floats(noiseSource(LATENT_CHANNELS * latentFrames), intArrayOf(1, LATENT_CHANNELS, latentFrames))
        val latentMask = Tensor.floats(FloatArray(latentFrames) { 1f }, intArrayOf(1, 1, latentFrames))
        val totalStepTensor = Tensor.scalarFloat(DEFAULT_TOTAL_STEPS.toFloat())

        repeat(DEFAULT_TOTAL_STEPS) { step ->
            val outputs =
                loaded.vectorEstSession.run(
                    mapOf(
                        NOISY_LATENT_KEY to noisyLatent,
                        TEXT_EMB_KEY to textEmbTensor,
                        STYLE_TTL_KEY to styleTtlTensor,
                        TEXT_MASK_KEY to textMaskTensor,
                        LATENT_MASK_KEY to latentMask,
                        CURRENT_STEP_KEY to Tensor.scalarFloat(step.toFloat()),
                        TOTAL_STEP_KEY to totalStepTensor,
                    ),
                )
            noisyLatent = outputs.singleTensorOrError(engineLabel)
        }
        return noisyLatent
    }

    /**
     * `ceil(duration_seconds * sampleRate / (base_chunk_size * chunk_compress_factor))`, at least
     * one frame - mirrors `supertonic-py/supertonic/core.py`'s `sample_noisy_latent` /
     * the official Java `TextToSpeech.sampleNoisyLatent` exactly (both compute this the same way).
     */
    private fun latentFrameCount(durationSeconds: Float): Int {
        val wavLenMax = (durationSeconds * SAMPLE_RATE_HZ).toLong().coerceAtLeast(0)
        val chunkSize = BASE_CHUNK_SIZE * CHUNK_COMPRESS_FACTOR
        return ((wavLenMax + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
    }

    private fun resolveLanguage(params: SynthesisParams): String {
        val index = params.value(LANGUAGE_PARAMETER_ID, DEFAULT_LANGUAGE_INDEX.toFloat()).toInt()
        return LANGUAGE_CHOICES.getOrElse(index) { DEFAULT_LANGUAGE }
    }

    /**
     * Reads each discovered voice's `voice_styles/<name>.json` bytes into [SupertonicStyle]
     * vectors. A voice with no recorded asset path (the [DEFAULT_VOICE] `forcedMatch` fallback,
     * which [buildAssetPaths] deliberately never adds a path for - see its KDoc) or an
     * unparsable/missing file is skipped rather than failing `load()` outright - it then fails
     * loudly, not silently, the moment [synthesizeSentence] is asked for that specific voice
     * (mirrors [com.phonetts.engines.f5tts.F5TtsEngine]'s reference-clip loading).
     */
    private fun loadStyles(descriptor: ModelDescriptor): Map<String, SupertonicStyle> =
        descriptor.voices
            .mapNotNull { voice ->
                val path = descriptor.assetPaths[voiceStyleAssetKey(voice.id)] ?: return@mapNotNull null
                val style = SupertonicStyle.parse(textReader(path)) ?: return@mapNotNull null
                voice.id to style
            }.toMap()

    /** Voices discovered by file name under `voice_styles/` - never a hardcoded `M1..F5` list (rule 1). */
    private fun discoverVoices(bundle: ModelBundle): List<Voice> =
        bundle.fileNames
            .filter { it.startsWith(VOICE_STYLES_DIR) && it.endsWith(VOICE_STYLE_SUFFIX) }
            .map { it.removePrefix(VOICE_STYLES_DIR).removeSuffix(VOICE_STYLE_SUFFIX) }
            .filter { it.isNotBlank() }
            .sorted()
            .map { name -> Voice(id = name, name = name, language = DEFAULT_LANGUAGE) }

    private fun buildDescriptor(
        bundle: ModelBundle,
        discoveredVoices: List<Voice>,
        origin: Origin,
    ): ModelDescriptor {
        val voices = discoveredVoices.ifEmpty { listOf(DEFAULT_VOICE) }
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = origin,
            // VALIDATED (docs/research/supertonic-facts.md): onnx/tts.json's "ae.sample_rate" is
            // 44100 across the Supertonic 1/2/3 family (same architecture/export shape) - a fixed
            // family constant recorded here, the one sanctioned place for it (spec §5.7), same
            // posture as KittenEngine.SAMPLE_RATE.
            sampleRate = SAMPLE_RATE_HZ,
            voices = voices,
            defaultVoiceId = voices.first().id,
            parameters = listOf(ModelParameter.speed(SPEED_RANGE, DEFAULT_SPEED), languageParameter()),
            assetPaths = buildAssetPaths(bundle, discoveredVoices),
            // A-priori estimate (issue #38): on-disk onnx/ weights sum to ~398 MiB
            // (3.7 + 36.4 + 256.5 + 101.4 MiB, docs/research/supertonic-facts.md file listing);
            // budgeted with headroom for the flow-matching loop's intermediate buffers. Refined by
            // observed peak RAM of previous loads.
            resourceCost = ResourceCost.peakRamMebibytes(PEAK_RAM_MIB),
        )
    }

    private fun languageParameter(): ModelParameter =
        ModelParameter(
            id = LANGUAGE_PARAMETER_ID,
            displayName = "Language",
            kind = ModelParameter.Kind.CHOICE,
            choices = LANGUAGE_CHOICES,
            default = DEFAULT_LANGUAGE_INDEX.toFloat(),
        )

    /**
     * Every asset path EXCEPT a voice style is unconditional (the four graphs + config + indexer
     * are structurally required by [forcedMatch]); a voice style path is added only for
     * [discoveredVoices] - deliberately NOT for the synthesized [DEFAULT_VOICE] fallback, which has
     * no real file behind it (see [loadStyles]'s KDoc for what that omission causes downstream).
     */
    private fun buildAssetPaths(
        bundle: ModelBundle,
        discoveredVoices: List<Voice>,
    ): Map<String, String> {
        val paths =
            mutableMapOf(
                DP_ASSET to joinAssetPath(bundle, DP_FILE),
                TEXT_ENCODER_ASSET to joinAssetPath(bundle, TEXT_ENCODER_FILE),
                VECTOR_ESTIMATOR_ASSET to joinAssetPath(bundle, VECTOR_ESTIMATOR_FILE),
                VOCODER_ASSET to joinAssetPath(bundle, VOCODER_FILE),
                CONFIG_ASSET to joinAssetPath(bundle, CONFIG_FILE),
                INDEXER_ASSET to joinAssetPath(bundle, INDEXER_FILE),
            )
        for (voice in discoveredVoices) {
            val voiceStyleFile = "$VOICE_STYLES_DIR${voice.id}$VOICE_STYLE_SUFFIX"
            paths[voiceStyleAssetKey(voice.id)] = joinAssetPath(bundle, voiceStyleFile)
        }
        return paths
    }

    private fun voiceStyleAssetKey(voiceId: String): String = "$VOICE_STYLE_ASSET_PREFIX$voiceId"

    /** Everything the loaded engine needs to synthesize; null while unloaded (spec §5.5). */
    private class LoadedState(
        val descriptor: ModelDescriptor,
        val dpSession: InferenceSession,
        val textEncSession: InferenceSession,
        val vectorEstSession: InferenceSession,
        val vocoderSession: InferenceSession,
        val frontend: SupertonicFrontend,
        val styles: Map<String, SupertonicStyle>,
    )

    companion object {
        const val ENGINE_ID = "supertonic"
        const val DISPLAY_NAME = "Supertonic"

        /** id of the [com.phonetts.core.runtime.Runtime] this engine runs on. */
        const val RUNTIME_ID = "onnx"

        // VALIDATED (docs/research/supertonic-facts.md): Supertone/supertonic-3's onnx/tts.json
        // "ae.sample_rate", cross-checked against supertonic-py's Supertonic.sample_rate and the
        // official Java Config.AEConfig.sampleRate.
        const val SAMPLE_RATE_HZ = 44_100

        // Real onnx/<name>.onnx + onnx/tts.json + onnx/unicode_indexer.json file names shipped by
        // Supertone/supertonic{,-2,-3} (VALIDATED via Hugging Face repo listing, 2026-07-24).
        const val DP_FILE = "onnx/duration_predictor.onnx"
        const val TEXT_ENCODER_FILE = "onnx/text_encoder.onnx"
        const val VECTOR_ESTIMATOR_FILE = "onnx/vector_estimator.onnx"
        const val VOCODER_FILE = "onnx/vocoder.onnx"
        const val CONFIG_FILE = "onnx/tts.json"
        const val INDEXER_FILE = "onnx/unicode_indexer.json"

        private val REQUIRED_ONNX_FILES = listOf(DP_FILE, TEXT_ENCODER_FILE, VECTOR_ESTIMATOR_FILE, VOCODER_FILE)
        private val REQUIRED_FILES = REQUIRED_ONNX_FILES + listOf(CONFIG_FILE, INDEXER_FILE)

        // A distinctive key from the real onnx/tts.json (docs/research/supertonic-facts.md) that a
        // foreign bundle coincidentally reusing these six file NAMES would not carry - defense in
        // depth on top of the already-distinctive filename fingerprint above.
        private const val CONFIG_MARKER = "chunk_compress_factor"

        const val VOICE_STYLES_DIR = "voice_styles/"
        const val VOICE_STYLE_SUFFIX = ".json"
        private const val VOICE_STYLE_ASSET_PREFIX = "voiceStyle:"

        const val DP_ASSET = "durationPredictor"
        const val TEXT_ENCODER_ASSET = "textEncoder"
        const val VECTOR_ESTIMATOR_ASSET = "vectorEstimator"
        const val VOCODER_ASSET = "vocoder"
        const val CONFIG_ASSET = "config"
        const val INDEXER_ASSET = "indexer"

        // VALIDATED literal ONNX input/output tensor names, quoted from
        // supertonic-py/supertonic/core.py's Supertonic.__call__ and confirmed byte-for-byte
        // against the official Java Helper.java's TextToSpeech._infer (see class KDoc).
        const val TEXT_IDS_KEY = "text_ids"
        const val STYLE_DP_KEY = "style_dp"
        const val STYLE_TTL_KEY = "style_ttl"
        const val TEXT_MASK_KEY = "text_mask"
        const val NOISY_LATENT_KEY = "noisy_latent"
        const val TEXT_EMB_KEY = "text_emb"
        const val LATENT_MASK_KEY = "latent_mask"
        const val CURRENT_STEP_KEY = "current_step"
        const val TOTAL_STEP_KEY = "total_step"
        const val LATENT_KEY = "latent"

        // VALIDATED (docs/research/supertonic-facts.md): supertonic-py/supertonic/config.py
        // MIN_SPEED/MAX_SPEED/DEFAULT_SPEED.
        val SPEED_RANGE = 0.7f..2.0f
        const val DEFAULT_SPEED = 1.05f

        const val LANGUAGE_PARAMETER_ID = "language"
        const val DEFAULT_LANGUAGE = "en"

        // SupertonicFrontend.SUPPORTED_LANGUAGES (31 codes) + the "na"/language-agnostic fallback -
        // the CHOICE ModelParameter's options, in the same order as the real config.py list (see
        // class KDoc "LANGUAGE").
        val LANGUAGE_CHOICES: List<String> =
            SupertonicFrontend.SUPPORTED_LANGUAGES + SupertonicFrontend.UNKNOWN_LANGUAGE
        private val DEFAULT_LANGUAGE_INDEX = LANGUAGE_CHOICES.indexOf(DEFAULT_LANGUAGE).coerceAtLeast(0)

        // onnx/tts.json: ae.base_chunk_size=512, ttl.chunk_compress_factor=6, ttl.latent_dim=24 -
        // fixed across the exported graphs of this family (not a per-bundle variable), so recorded
        // here as validated constants rather than re-parsed from tts.json at load time (same
        // posture as SAMPLE_RATE_HZ above).
        private const val BASE_CHUNK_SIZE = 512
        private const val CHUNK_COMPRESS_FACTOR = 6
        private const val LATENT_DIM = 24
        private const val LATENT_CHANNELS = LATENT_DIM * CHUNK_COMPRESS_FACTOR

        // supertonic-py/supertonic/config.py DEFAULT_TOTAL_STEPS. NOT a ModelParameter - see class
        // KDoc "STEPS" for why.
        private const val DEFAULT_TOTAL_STEPS = 8

        // A-priori peak RAM estimate (issue #38) - see buildDescriptor()'s call site for the math.
        private const val PEAK_RAM_MIB = 700L

        /** `Voice(id="default", ...)` used only when a [forcedMatch] bundle has no voice styles at all. */
        val DEFAULT_VOICE = Voice(id = "default", name = "Default", language = DEFAULT_LANGUAGE)
    }
}

// Box-Muller Gaussian sampler for the flow-matching loop's initial noisy latent - matches the
// reference implementations' own approach (supertonic-py uses np.random.randn; the official Java
// example implements Box-Muller explicitly in TextToSpeech.sampleNoisyLatent). The exact RNG
// algorithm has no bearing on correctness (this is randomly-sampled noise a diffusion process
// denoises away, not a value being round-tripped) - only that it is roughly standard-normal.
private fun sampleGaussianNoise(size: Int): FloatArray {
    val random = Random.Default
    return FloatArray(size) {
        val u1 = (1.0 - random.nextDouble()).coerceAtLeast(MIN_UNIFORM)
        val u2 = random.nextDouble()
        (sqrt(BOX_MULLER_LN_COEFF * ln(u1)) * cos(BOX_MULLER_ANGLE * u2)).toFloat()
    }
}

private const val MIN_UNIFORM = 1e-10

// Box-Muller transform coefficients (z = sqrt(-2·ln u1) · cos(2π·u2)) - named to satisfy detekt
// MagicNumber and to document the standard formula.
private const val BOX_MULLER_LN_COEFF = -2.0
private val BOX_MULLER_ANGLE = 2.0 * PI
