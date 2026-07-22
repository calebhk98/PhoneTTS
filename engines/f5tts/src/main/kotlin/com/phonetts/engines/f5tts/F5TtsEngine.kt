package com.phonetts.engines.f5tts

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
import java.io.File

/**
 * F5-TTS — a flow-matching (CFM) diffusion-transformer voice-CLONING model, the hardest engine
 * in this registry after CosyVoice2: three ONNX graphs (preprocess / DiT transformer / Vocos
 * decoder) wired through an ODE denoising loop, and — unlike every other engine here — no baked
 * voice table at all. See `README-io.md` in this module for the full researched pipeline, every
 * tensor name/shape/dtype claim's source, and an explicit list of what is implemented vs. TODO.
 *
 * ASSUMPTION (not runnable against real ONNX graphs in this environment, spec §9's honesty rule):
 * every tensor key below is the LITERAL `input_names`/`output_names` string quoted from
 * `DakeQQ/F5-TTS-ONNX`'s `Export_ONNX/F5_TTS/Export_F5.py` (a stable, citable export contract —
 * the same confidence level as [com.phonetts.engines.piper.PiperEngine]'s VITS signature, i.e.
 * "we know the names, not that this exact orchestration is byte-correct against the graph").
 * Dtype/shape details Export_F5.py doesn't pin down are called out individually below.
 *
 * Bundle fingerprint (spec §9.1, fail-closed): [inspect] claims a bundle only when it has all
 * three ONNX graphs AND `vocab.txt` — that four-file set (mirroring
 * [com.phonetts.engines.cosyvoice2.CosyVoice2Engine]'s four-GGUF-stage fingerprint) is confirmed
 * by two independent HuggingFace re-uploads of the DakeQQ export to always ship together.
 *
 * Voices: F5 clones from a reference clip, so a "voice" here is a bundled `<name>.reference.wav`
 * + `<name>.reference.txt` pair (this engine's own convention, not an upstream standard — see
 * `README-io.md`). A bundle with none still loads (the graphs are independently usable) but
 * exposes only the placeholder [DEFAULT_VOICE], which has no backing clip and so fails loudly, not
 * silently, at synthesis time (spec rule 4).
 */
internal class F5TtsEngine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable with no real files on disk (parity with
    // MeloEngine's fileReader / PiperEngine's sidecarReader) — production reads real files.
    private val textReader: (path: String) -> String = { File(it).readText() },
    private val audioReader: (path: String) -> F5WavDecoder.Decoded = { F5WavDecoder.decode(File(it).readBytes()) },
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = DISPLAY_NAME

    private var state: LoadedState? = null

    override fun isLoaded(): Boolean = state != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        if (!CORE_FILES.all { bundle.hasFile(it) }) return null
        return EngineMatch(id, buildDescriptor(bundle, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val weightFiles = listOf(PREPROCESS_FILE, TRANSFORMER_FILE, DECODE_FILE)
        require(weightFiles.all { bundle.hasFile(it) }) {
            "F5-TTS forcedMatch requires all three ONNX graphs (${weightFiles.joinToString()}) in" +
                " bundle '${bundle.id}' — the pipeline is unusable with only some of them"
        }
        return EngineMatch(id, buildDescriptor(bundle, Origin.SIDELOADED))
    }

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, ONNX_RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        unload()
        val runtime = requireRuntime(context, ONNX_RUNTIME_ID, engineLabel)
        val loaded =
            openWithRollback { opened ->
                val preprocessPath = requireAssetPath(descriptor, PREPROCESS_ASSET, engineLabel)
                val transformerPath = requireAssetPath(descriptor, TRANSFORMER_ASSET, engineLabel)
                val decodePath = requireAssetPath(descriptor, DECODE_ASSET, engineLabel)
                val vocabPath = requireAssetPath(descriptor, VOCAB_ASSET, engineLabel)

                val preprocess = runtime.createSession(preprocessPath).also(opened::add)
                val transformer = runtime.createSession(transformerPath).also(opened::add)
                val decode = runtime.createSession(decodePath).also(opened::add)
                val vocab = F5Vocab.parse(textReader(vocabPath))
                val references = loadReferenceClips(descriptor)

                LoadedState(descriptor, preprocess, transformer, decode, vocab, references)
            }
        state = loaded
    }

    override fun unload() {
        state?.let { closeAllQuietly(it.preprocess, it.transformer, it.decode) }
        state = null
    }

    override fun voices(): List<Voice> = state?.descriptor?.voices ?: listOf(DEFAULT_VOICE)

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val loaded = checkNotNull(state) { "$engineLabel.synthesizeSentence called before load()" }
        val reference =
            loaded.references[voiceId] ?: error(
                "$engineLabel: voice '$voiceId' has no bundled reference clip" +
                    " (<name>$REFERENCE_AUDIO_SUFFIX + <name>$REFERENCE_TEXT_SUFFIX) — F5-TTS needs a" +
                    " reference clip to clone a voice, see README-io.md",
            )

        // DakeQQ's driver concatenates ref text and gen text with NO separator before tokenizing
        // (`convert_char_to_pinyin([ref_text + gen_text])`) — mirrored verbatim, not a guess.
        val combinedText = reference.text + sentence
        val tokenIds = F5Vocab.tokenIds(combinedText, loaded.vocab)
        val maxDuration =
            F5DurationPlanner.maxDurationFrames(
                refAudioFrames = F5DurationPlanner.refAudioFrames(reference.audio.size),
                refTextLength = reference.text.length,
                genTextLength = sentence.length,
                speed = params.speed,
            )

        val preprocessOut = loaded.preprocess.run(preprocessInputs(reference.audio, tokenIds, maxDuration))
        return runDenoisingAndDecode(loaded, preprocessOut)
    }

    private fun preprocessInputs(
        refAudio: FloatArray,
        tokenIds: LongArray,
        maxDuration: Long,
    ): Map<String, Tensor> =
        mapOf(
            AUDIO_TENSOR to Tensor.floats(refAudio, intArrayOf(1, 1, refAudio.size)),
            TEXT_IDS_TENSOR to Tensor.longs(tokenIds, intArrayOf(1, tokenIds.size)),
            MAX_DURATION_TENSOR to Tensor.longs(longArrayOf(maxDuration)),
        )

    /**
     * The ODE/CFG denoising loop (`README-io.md`): CFG is baked INTO `F5_Transformer.onnx` at
     * export time — both the conditioned (`cat_mel_text`) and unconditioned (`cat_mel_text_drop`)
     * passes happen inside ONE `run()`, combined with a `cfg_strength` frozen into the graph — so
     * there is no separate CFG call here, only the NFE Euler-step loop. [TRANSFORMER_STEPS] is the
     * DakeQQ default (`NFE_STEP=32`, looped `NFE_STEP-1` times); see `README-io.md` for why this
     * can't be discovered from the loaded graph and is therefore NOT a [ModelParameter].
     */
    private fun runDenoisingAndDecode(
        loaded: LoadedState,
        preprocessOut: Map<String, Tensor>,
    ): FloatArray {
        val ropeCosQ = preprocessOut.tensorOrError(ROPE_COS_Q_TENSOR, engineLabel)
        val ropeSinQ = preprocessOut.tensorOrError(ROPE_SIN_Q_TENSOR, engineLabel)
        val ropeCosK = preprocessOut.tensorOrError(ROPE_COS_K_TENSOR, engineLabel)
        val ropeSinK = preprocessOut.tensorOrError(ROPE_SIN_K_TENSOR, engineLabel)
        val catMelText = preprocessOut.tensorOrError(CAT_MEL_TEXT_TENSOR, engineLabel)
        val catMelTextDrop = preprocessOut.tensorOrError(CAT_MEL_TEXT_DROP_TENSOR, engineLabel)
        val refSignalLen = preprocessOut.tensorOrError(REF_SIGNAL_LEN_TENSOR, engineLabel)

        var noise = preprocessOut.tensorOrError(NOISE_TENSOR, engineLabel)
        // ASSUMPTION: the loop's first time_step seed. Not shown in the quoted inference-script
        // excerpt (it only shows the loop body); 0 matches a freshly-initialized Euler integration
        // and needs on-device confirmation.
        var timeStep = Tensor.longs(longArrayOf(INITIAL_TIME_STEP))

        repeat(TRANSFORMER_STEPS) {
            val transformerOut =
                loaded.transformer.run(
                    mapOf(
                        NOISE_TENSOR to noise,
                        ROPE_COS_Q_TENSOR to ropeCosQ,
                        ROPE_SIN_Q_TENSOR to ropeSinQ,
                        ROPE_COS_K_TENSOR to ropeCosK,
                        ROPE_SIN_K_TENSOR to ropeSinK,
                        CAT_MEL_TEXT_TENSOR to catMelText,
                        CAT_MEL_TEXT_DROP_TENSOR to catMelTextDrop,
                        TIME_STEP_TENSOR to timeStep,
                    ),
                )
            // Mirrors the reference driver exactly: `noise, time_step = ort_session_B.run(...)` —
            // the transformer's own "denoised" output becomes next iteration's "noise" input.
            noise = transformerOut.tensorOrError(DENOISED_TENSOR, engineLabel)
            timeStep = transformerOut.tensorOrError(TIME_STEP_TENSOR, engineLabel)
        }

        val decodeOut =
            loaded.decode.run(
                mapOf(
                    DENOISED_TENSOR to noise,
                    REF_SIGNAL_LEN_TENSOR to refSignalLen,
                ),
            )
        return decodeOut.floatsOrError(OUTPUT_AUDIO_TENSOR, engineLabel)
    }

    private fun loadReferenceClips(descriptor: ModelDescriptor): Map<String, ReferenceClip> =
        descriptor.voices.mapNotNull { voice ->
            val audioPath = descriptor.assetPaths[referenceAudioAssetKey(voice.id)] ?: return@mapNotNull null
            val textPath = descriptor.assetPaths[referenceTextAssetKey(voice.id)] ?: return@mapNotNull null
            // ASSUMPTION: a bundled reference clip is already at F5's 24 kHz model sample rate.
            // This module has no resampler (and resampling a REFERENCE clip is a different concern
            // from CLAUDE.md rule 2's ban on resampling OUTPUT to fake speed — but it is still an
            // unverified assumption, so it's called out here, not silently relied on).
            val audio = audioReader(audioPath).samples
            val text = textReader(textPath)
            voice.id to ReferenceClip(audio, text)
        }.toMap()

    private fun discoveredReferenceVoices(bundle: ModelBundle): List<Voice> =
        bundle.fileNames
            .filter { it.endsWith(REFERENCE_AUDIO_SUFFIX) }
            .mapNotNull { audioFile ->
                val base = audioFile.removeSuffix(REFERENCE_AUDIO_SUFFIX)
                base.takeIf { bundle.hasFile(it + REFERENCE_TEXT_SUFFIX) }
            }.sorted()
            .map { base -> Voice(id = base, name = prettify(base), language = DEFAULT_LANGUAGE) }

    private fun buildDescriptor(
        bundle: ModelBundle,
        origin: Origin,
    ): ModelDescriptor {
        val discovered = discoveredReferenceVoices(bundle)
        val voices = discovered.ifEmpty { listOf(DEFAULT_VOICE) }
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = origin,
            sampleRate = SAMPLE_RATE_HZ,
            voices = voices,
            defaultVoiceId = voices.first().id,
            // Introspected (README-io.md "Speed: routes to max_duration"): the Preprocess graph has
            // a native `max_duration` (mel-frame count) input that F5DurationPlanner routes speed
            // through — never resampled output audio (CLAUDE.md rule 2). NFE/CFG are baked into
            // F5_Transformer.onnx at export time, not a runtime input the loaded graph exposes, so —
            // unlike speed — they are deliberately NOT declared here (see README-io.md).
            parameters = listOf(ModelParameter.speed(SPEED_RANGE, DEFAULT_SPEED)),
            assetPaths = buildAssetPaths(bundle, discovered),
            // Approximate peak-RAM estimate (issue #38): F5_Transformer.onnx alone is roughly
            // 650 MB-1.3 GB depending on precision (huggingfacess/CreativeHub008 F5-TTS-ONNX
            // re-uploads); plus the two smaller graphs. A-priori only, refined by observed peak RAM.
            resourceCost = ResourceCost.peakRamMebibytes(PEAK_RAM_MIB),
        )
    }

    private fun buildAssetPaths(
        bundle: ModelBundle,
        discoveredVoices: List<Voice>,
    ): Map<String, String> {
        val paths =
            mutableMapOf(
                PREPROCESS_ASSET to joinAssetPath(bundle, PREPROCESS_FILE),
                TRANSFORMER_ASSET to joinAssetPath(bundle, TRANSFORMER_FILE),
                DECODE_ASSET to joinAssetPath(bundle, DECODE_FILE),
                VOCAB_ASSET to joinAssetPath(bundle, VOCAB_FILE),
            )
        for (voice in discoveredVoices) {
            paths[referenceAudioAssetKey(voice.id)] = joinAssetPath(bundle, voice.id + REFERENCE_AUDIO_SUFFIX)
            paths[referenceTextAssetKey(voice.id)] = joinAssetPath(bundle, voice.id + REFERENCE_TEXT_SUFFIX)
        }
        return paths
    }

    private fun referenceAudioAssetKey(voiceId: String): String = voiceId + REFERENCE_AUDIO_ASSET_SUFFIX

    private fun referenceTextAssetKey(voiceId: String): String = voiceId + REFERENCE_TEXT_ASSET_SUFFIX

    private fun prettify(voiceId: String): String = voiceId.replace('_', ' ').replace('-', ' ')

    /** Everything the loaded engine needs to synthesize; null while unloaded (spec §5.5). */
    private class LoadedState(
        val descriptor: ModelDescriptor,
        val preprocess: InferenceSession,
        val transformer: InferenceSession,
        val decode: InferenceSession,
        val vocab: Map<String, Int>,
        val references: Map<String, ReferenceClip>,
    )

    private class ReferenceClip(
        val audio: FloatArray,
        val text: String,
    )

    companion object {
        const val ENGINE_ID = "f5tts"
        private const val DISPLAY_NAME = "F5-TTS"
        private const val ONNX_RUNTIME_ID = "onnx"

        // The bundle fingerprint (README-io.md): all three graphs + vocab.txt, confirmed by two
        // independent HF re-uploads of DakeQQ/F5-TTS-ONNX's export to always ship together.
        const val PREPROCESS_FILE = "F5_Preprocess.onnx"
        const val TRANSFORMER_FILE = "F5_Transformer.onnx"
        const val DECODE_FILE = "F5_Decode.onnx"
        const val VOCAB_FILE = "vocab.txt"
        private val CORE_FILES = listOf(PREPROCESS_FILE, TRANSFORMER_FILE, DECODE_FILE, VOCAB_FILE)

        const val PREPROCESS_ASSET = "preprocess"
        const val TRANSFORMER_ASSET = "transformer"
        const val DECODE_ASSET = "decode"
        const val VOCAB_ASSET = "vocab"
        private const val REFERENCE_AUDIO_ASSET_SUFFIX = ".referenceAudio"
        private const val REFERENCE_TEXT_ASSET_SUFFIX = ".referenceText"

        // This engine's own bundle convention for a voice's reference clip (README-io.md "Voice =
        // a bundled reference clip") — not an upstream standard.
        const val REFERENCE_AUDIO_SUFFIX = ".reference.wav"
        const val REFERENCE_TEXT_SUFFIX = ".reference.txt"

        private const val SAMPLE_RATE_HZ = 24_000
        private const val DEFAULT_LANGUAGE = "en"
        val DEFAULT_VOICE = Voice(id = "reference", name = "Reference Voice", language = DEFAULT_LANGUAGE)

        private val SPEED_RANGE = 0.5f..2.0f
        private const val DEFAULT_SPEED = 1.0f

        // DakeQQ default NFE_STEP=32, looped NFE_STEP-1 times (README-io.md "NFE step count...
        // baked in") — an internal orchestration constant, NOT a ModelParameter (see class KDoc).
        private const val TRANSFORMER_STEPS = 31
        private const val INITIAL_TIME_STEP = 0L

        // Approximate peak resident RAM (MiB) while loaded — F5_Transformer.onnx dominates.
        private const val PEAK_RAM_MIB = 1400L

        // Literal ONNX tensor names, quoted from Export_F5.py's torch.onnx.export calls (see class
        // KDoc's ASSUMPTION note for what "literal" does and doesn't cover here).
        private const val AUDIO_TENSOR = "audio"
        private const val TEXT_IDS_TENSOR = "text_ids"
        private const val MAX_DURATION_TENSOR = "max_duration"
        private const val NOISE_TENSOR = "noise"
        private const val ROPE_COS_Q_TENSOR = "rope_cos_q"
        private const val ROPE_SIN_Q_TENSOR = "rope_sin_q"
        private const val ROPE_COS_K_TENSOR = "rope_cos_k"
        private const val ROPE_SIN_K_TENSOR = "rope_sin_k"
        private const val CAT_MEL_TEXT_TENSOR = "cat_mel_text"
        private const val CAT_MEL_TEXT_DROP_TENSOR = "cat_mel_text_drop"
        private const val REF_SIGNAL_LEN_TENSOR = "ref_signal_len"
        private const val TIME_STEP_TENSOR = "time_step"
        private const val DENOISED_TENSOR = "denoised"
        private const val OUTPUT_AUDIO_TENSOR = "output_audio"
    }
}
