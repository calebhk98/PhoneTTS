package com.phonetts.engines.mms

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.model.ResourceCost
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Tensor
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.closeAllQuietly
import com.phonetts.engines.common.floatsOrError
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.requireVoiceIndex
import java.io.File

/**
 * The MMS / Facebook-VITS engine (spec Phase 2, `Xenova/mms-tts-*` family) - Meta's Massively
 * Multilingual Speech VITS exports, ~1,100 languages, each its own single-speaker ONNX bundle.
 *
 * ONNX GRAPH I/O - VALIDATED, not assumed (unlike a purely-documented "ASSUMPTION" comment,
 * this was checked directly): `onnx/model_quantized.onnx` was downloaded from the real
 * `Xenova/mms-tts-eng` repo and loaded with `onnx.load(..., load_external_data=False)`
 * (2026-07-22). The graph reports:
 * ```
 * inputs:  input_ids       INT64  [text_batch_size, sequence_length]
 *          attention_mask  INT64  [text_batch_size, sequence_length]
 * outputs: waveform        FLOAT  [text_batch_size, n_samples]
 *          spectrogram     FLOAT  [text_batch_size, 192, num_bins]
 * ```
 * Confirmed by the transformers.js `VitsModel`/`sessionRun` source (`packages/transformers/src/
 * models/vits/modeling_vits.js`, `huggingface/transformers.js`): callers read back `waveform`.
 * This engine feeds `input_ids` + an all-ones `attention_mask` of the same length (batch size is
 * always 1 - one sentence per [InferenceSession.run] call, spec rule 8) and reads `waveform`;
 * `spectrogram` is an intermediate the end-to-end flow-based vocoder produces and is unused here.
 *
 * SPEED - **no native knob, so none is advertised** (CLAUDE.md rule 2): the graph above has
 * exactly two inputs, neither a length/duration/speed scalar. `config.json`'s `speaking_rate`
 * field is a Python-side generation-config default baked into the export at convert time, not a
 * runtime input - there is nothing to route a UI speed value onto. [buildDescriptor] therefore
 * declares `parameters = emptyList()`, which makes [ModelDescriptor.speedRange] lock to `1.0..1.0`
 * automatically (never resampling output to fake speed, rule 2's "never" case).
 *
 * VOICES - one per bundle (every real `Xenova/mms-tts-<lang>` repo is single-speaker,
 * `config.json`'s `num_speakers` is always `1`), so unlike Piper's per-file-or-per-speaker fan-out
 * this engine loads exactly one [InferenceSession] and exposes exactly one [Voice].
 */
internal class MmsEngine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable without real files on disk, mirroring
    // PiperEngine's sidecarReader / KokoroEngine's textFileReader.
    private val sideFileReader: (path: String) -> String? = ::readSideFile,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = displayName

    private var session: InferenceSession? = null
    private var frontend: MmsFrontend? = null
    private var loadedDescriptor: ModelDescriptor? = null

    override fun isLoaded(): Boolean = session != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val weightsFile = selectWeightsFile(bundle) ?: return null
        val config = bundle.sideFile(CONFIG_FILE)?.let(MmsModelConfig::parse) ?: return null
        if (!config.isMmsVits) return null
        val vocab = bundle.sideFile(VOCAB_FILE)?.let(MmsVocab::parse) ?: return null
        val tokenizerConfig = bundle.sideFile(TOKENIZER_CONFIG_FILE)?.let(MmsTokenizerConfig::parse)
        val descriptor = buildDescriptor(bundle, weightsFile, config, tokenizerConfig, Origin.BUILT_IN)
        return EngineMatch(id, descriptor)
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val weightsFile =
            selectWeightsFile(bundle)
                ?: throw IllegalArgumentException(
                    "MMS forcedMatch requires at least one .onnx file in bundle '${bundle.id}'",
                )
        val config = bundle.sideFile(CONFIG_FILE)?.let(MmsModelConfig::parse)
        val tokenizerConfig = bundle.sideFile(TOKENIZER_CONFIG_FILE)?.let(MmsTokenizerConfig::parse)
        val descriptor = buildDescriptor(bundle, weightsFile, config, tokenizerConfig, Origin.SIDELOADED)
        return EngineMatch(id, descriptor)
    }

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, ONNX_RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        unload()
        val runtime = requireRuntime(context, ONNX_RUNTIME_ID, engineLabel)
        val weightsPath = requireAssetPath(descriptor, WEIGHTS_ASSET, engineLabel)
        val vocab = descriptor.assetPaths[VOCAB_ASSET]?.let(sideFileReader)?.let(MmsVocab::parse) ?: emptyMap()
        val tokenizerConfigPath = descriptor.assetPaths[TOKENIZER_CONFIG_ASSET]
        val tokenizerConfig = tokenizerConfigPath?.let(sideFileReader)?.let(MmsTokenizerConfig::parse)
        val padId = tokenizerConfig?.padToken?.let { vocab[it] } ?: DEFAULT_PAD_ID
        val addBlank = tokenizerConfig?.addBlank ?: MmsTokenizerConfig.FALLBACK.addBlank

        session = runtime.createSession(weightsPath)
        frontend = MmsFrontend(vocab, padId, addBlank)
        loadedDescriptor = descriptor
    }

    override fun unload() {
        closeAllQuietly(session)
        session = null
        frontend = null
        loadedDescriptor = null
    }

    override fun voices(): List<Voice> = loadedDescriptor?.voices ?: emptyList()

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val activeSession = session ?: error("$engineLabel.synthesizeSentence called before load()")
        val activeFrontend = frontend ?: error("$engineLabel.synthesizeSentence called before load()")
        val voices = loadedDescriptor?.voices ?: emptyList()
        requireVoiceIndex(voices, voiceId, engineLabel)
        val language = voices.first { it.id == voiceId }.language

        // No speed knob to route (see class KDoc) -- params carries only the model's OWN declared
        // parameters (none here), so nothing from params reaches the tensors below (rule 2).
        val input = activeFrontend.toModelInput(sentence, language)
        val attentionMask = LongArray(input.tokenIds.size) { 1L }
        val outputs =
            activeSession.run(
                mapOf(
                    INPUT_IDS_TENSOR to Tensor.longs(input.tokenIds, intArrayOf(1, input.tokenIds.size)),
                    ATTENTION_MASK_TENSOR to Tensor.longs(attentionMask, intArrayOf(1, attentionMask.size)),
                ),
            )
        return outputs.floatsOrError(WAVEFORM_TENSOR, engineLabel)
    }

    /**
     * Picks the ONE `.onnx` weights file this bundle loads, preferring the smallest/most
     * budget-hardware-friendly variant a real `Xenova/mms-tts-*` repo ships (`onnx/model.onnx`,
     * `onnx/model_fp16.onnx`, `onnx/model_quantized.onnx` - nested path, matches the real layout):
     * quantized > fp16 > full fp32 > whatever else ends in `.onnx` (picked deterministically by
     * name so tests are stable). Null only if the bundle has no `.onnx` file at all.
     */
    private fun selectWeightsFile(bundle: ModelBundle): String? {
        val onnxFiles = bundle.fileNames.filter { it.endsWith(ONNX_SUFFIX) }
        if (onnxFiles.isEmpty()) return null
        val preferred =
            WEIGHTS_PREFERENCE.firstNotNullOfOrNull { basename ->
                onnxFiles.firstOrNull { it.substringAfterLast('/') == basename }
            }
        return preferred ?: onnxFiles.sorted().first()
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        weightsFile: String,
        config: MmsModelConfig?,
        tokenizerConfig: MmsTokenizerConfig?,
        origin: Origin,
    ): ModelDescriptor {
        val language = tokenizerConfig?.language ?: MmsTokenizerConfig.FALLBACK.language
        val sampleRate = config?.samplingRate ?: FALLBACK_SAMPLE_RATE
        val voice = Voice(id = DEFAULT_VOICE_ID, name = "$DISPLAY_NAME - $language", language = language)
        val assetPaths = mutableMapOf(WEIGHTS_ASSET to joinAssetPath(bundle, weightsFile))
        if (bundle.hasFile(VOCAB_FILE)) {
            assetPaths[VOCAB_ASSET] = joinAssetPath(bundle, VOCAB_FILE)
        }
        if (bundle.hasFile(TOKENIZER_CONFIG_FILE)) {
            assetPaths[TOKENIZER_CONFIG_ASSET] = joinAssetPath(bundle, TOKENIZER_CONFIG_FILE)
        }

        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = "$DISPLAY_NAME - $language",
            origin = origin,
            sampleRate = sampleRate,
            voices = listOf(voice),
            defaultVoiceId = voice.id,
            // Introspected: the MMS-VITS ONNX graph exposes no length/duration input (see class
            // KDoc) -- no ModelParameter declared, so ModelDescriptor.speedRange locks to 1.0..1.0
            // (CLAUDE.md rule 2: a model with no native speed knob advertises a locked range).
            parameters = emptyList(),
            assetPaths = assetPaths,
            // A-priori estimate (issue #38): derived from the REAL measured onnx/model_quantized.onnx
            // size (~37 MiB) for Xenova/mms-tts-eng, plus runtime/activation-buffer margin. Refined by
            // observed peak RAM of previous loads.
            resourceCost = ResourceCost.peakRamMebibytes(PEAK_RAM_MIB),
        )
    }

    companion object {
        const val ENGINE_ID = "mms"
        const val DISPLAY_NAME = "MMS"

        private const val ONNX_SUFFIX = ".onnx"
        private const val ONNX_RUNTIME_ID = "onnx"

        private const val CONFIG_FILE = "config.json"
        private const val VOCAB_FILE = "vocab.json"
        private const val TOKENIZER_CONFIG_FILE = "tokenizer_config.json"

        private const val WEIGHTS_ASSET = "weights"
        private const val VOCAB_ASSET = "vocab"
        private const val TOKENIZER_CONFIG_ASSET = "tokenizerConfig"

        // Real Xenova/mms-tts-* onnx/ basenames, most budget-hardware-friendly first.
        private val WEIGHTS_PREFERENCE = listOf("model_quantized.onnx", "model_fp16.onnx", "model.onnx")

        private const val INPUT_IDS_TENSOR = "input_ids"
        private const val ATTENTION_MASK_TENSOR = "attention_mask"
        private const val WAVEFORM_TENSOR = "waveform"

        // VITS/MMS convention (verified in every bundle checked): id 0 is always both a real
        // grapheme AND the blank/pad token -- used only when tokenizer_config.json's own pad_token
        // can't be resolved against the loaded vocab (forcedMatch()'s best-effort path).
        private const val DEFAULT_PAD_ID = 0L

        private const val DEFAULT_VOICE_ID = "default"

        // Only used by forcedMatch()'s best-effort path when config.json is absent/unparsable --
        // never by inspect()'s fail-closed one (mirrors PiperVoiceConfig.DEFAULT_SAMPLE_RATE).
        private const val FALLBACK_SAMPLE_RATE = 16_000

        // Approximate peak resident RAM (MiB): measured onnx/model_quantized.onnx for
        // Xenova/mms-tts-eng is ~37 MiB on disk; budgeted with headroom for runtime buffers.
        private const val PEAK_RAM_MIB = 150L
    }
}

private fun readSideFile(path: String): String? {
    val file = File(path)
    if (!file.exists()) return null
    return file.readText()
}
