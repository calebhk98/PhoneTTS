package com.phonetts.engines.piper

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.Voice
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.runtime.InferenceSession
import com.phonetts.core.runtime.Runtime
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.text.TextChunker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * The Piper (VITS-family) engine — first Tier-A model, proves the espeak -> ONNX path end to
 * end (spec §4, Phase 2.3).
 *
 * LICENSE NOTE (spec §5.7 origin/display concern, docs/research/model-facts.md): this targets
 * the actively maintained **OHF-Voice/piper1-gpl fork, licensed GPL-3.0**. The original
 * rhasspy/piper (MIT) was archived October 2025. Any "Piper" build/voice download surfaced to
 * the user should be labelled accordingly — this engine does not itself bundle weights (spec
 * rule 7), it only loads whatever bundle the resolver hands it.
 *
 * A downloaded/sideloaded Piper bundle is one or more independent `<voice>.onnx` graphs, each
 * with its own `<voice>.onnx.json` sidecar (phoneme_id_map, audio.sample_rate, inference
 * defaults). Each such pair becomes one [Voice]; this engine keeps one [InferenceSession] per
 * loaded voice (spec rule 6 — "one engine loaded at a time" bounds the *engine*, not how many
 * voice graphs one Piper bundle may contain).
 *
 * [sidecarReader] is injected so tests can supply sidecar JSON without touching a real
 * filesystem; production uses the real-file default.
 */
internal class PiperEngine(
    private val context: EngineContext,
    private val sidecarReader: (path: String) -> String? = ::readSidecarFile,
) : VoiceEngine {
    override val id: String = ENGINE_ID
    override val displayName: String = "Piper"

    private var loadedVoices: Map<String, LoadedVoice> = emptyMap()
    private var loadedDescriptor: ModelDescriptor? = null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val entries =
            bundle.fileNames
                .filter { it.endsWith(ONNX_SUFFIX) }
                .mapNotNull { onnxFile -> validVoiceEntry(bundle, onnxFile) }
        if (entries.isEmpty()) return null
        return EngineMatch(id, buildDescriptor(bundle, entries, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val onnxFiles = bundle.fileNames.filter { it.endsWith(ONNX_SUFFIX) }
        require(onnxFiles.isNotEmpty()) {
            "Piper forcedMatch requires at least one .onnx file in bundle '${bundle.id}'"
        }
        val entries = onnxFiles.map { onnxFile -> forcedVoiceEntry(bundle, onnxFile) }
        return EngineMatch(id, buildDescriptor(bundle, entries, Origin.SIDELOADED))
    }

    override suspend fun load(descriptor: ModelDescriptor) {
        unload()
        val runtime =
            context.runtimes.get(ONNX_RUNTIME_ID)
                ?: error("Piper requires the '$ONNX_RUNTIME_ID' runtime, but none is registered")
        loadedVoices = descriptor.voices.associate { voice -> voice.id to loadVoice(descriptor, voice, runtime) }
        loadedDescriptor = descriptor
    }

    override fun unload() {
        loadedVoices.values.forEach { it.session.close() }
        loadedVoices = emptyMap()
        loadedDescriptor = null
    }

    override fun voices(): List<Voice> = loadedDescriptor?.voices ?: emptyList()

    override fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
    ): Flow<FloatArray> {
        require(speed > 0f) { "speed must be positive, was $speed" }
        val loadedVoice = loadedVoices[voiceId] ?: error("Piper voice '$voiceId' is not loaded")
        return flow {
            val frontend = PiperFrontend(context.phonemizer, loadedVoice.config.phonemeIdMap)
            TextChunker.intoSentences(text).forEach { sentence ->
                emit(synthesizeSentence(frontend, loadedVoice, sentence, speed))
            }
        }
    }

    private fun synthesizeSentence(
        frontend: PiperFrontend,
        loadedVoice: LoadedVoice,
        sentence: String,
        speed: Float,
    ): FloatArray {
        val input = frontend.toModelInput(sentence, loadedVoice.config.language)
        // Speed ALWAYS routes to the model's native length_scale (spec rule 2) — never resample
        // output audio. length_scale is INVERSE to speed: larger = slower. Anchor on the voice's
        // own config default (normally 1.0) so speed=1.0 reproduces the model's natural pace.
        val lengthScale = loadedVoice.config.defaultLengthScale / speed
        val outputs = loadedVoice.session.run(sessionInputs(input.tokenIds, loadedVoice, lengthScale))
        return outputs[OUTPUT_TENSOR]?.asFloats()
            ?: error("Piper ONNX session produced no '$OUTPUT_TENSOR' tensor")
    }

    // ASSUMPTION (not runnable against a real ONNX graph in this environment): mirrors the
    // upstream piper1-gpl VITS export signature — "input" (int64 phoneme ids, [1, T]),
    // "input_lengths" (int64 [1]), "scales" (float32 [noise_scale, length_scale, noise_w]).
    private fun sessionInputs(
        tokenIds: LongArray,
        loadedVoice: LoadedVoice,
        lengthScale: Float,
    ): Map<String, Tensor> =
        mapOf(
            INPUT_TENSOR to Tensor.longs(tokenIds, intArrayOf(1, tokenIds.size)),
            INPUT_LENGTHS_TENSOR to Tensor.longs(longArrayOf(tokenIds.size.toLong())),
            SCALES_TENSOR to
                Tensor.floats(
                    floatArrayOf(loadedVoice.config.noiseScale, lengthScale, loadedVoice.config.noiseW),
                    intArrayOf(SCALES_SIZE),
                ),
        )

    private fun loadVoice(
        descriptor: ModelDescriptor,
        voice: Voice,
        runtime: Runtime,
    ): LoadedVoice {
        val onnxPath =
            descriptor.assetPaths[assetKey(voice.id, ONNX_SUFFIX)]
                ?: error("no onnx asset path recorded for Piper voice '${voice.id}'")
        val configPath = descriptor.assetPaths[assetKey(voice.id, SIDECAR_SUFFIX)]
        val config = configPath?.let(sidecarReader)?.let(PiperVoiceConfig::parse) ?: PiperVoiceConfig.fallback()
        return LoadedVoice(runtime.createSession(onnxPath), config)
    }

    private fun validVoiceEntry(
        bundle: ModelBundle,
        onnxFile: String,
    ): PiperVoiceEntry? {
        val sidecarName = onnxFile + SIDECAR_EXTRA_SUFFIX
        if (!bundle.hasFile(sidecarName)) return null
        val sidecarText = bundle.sideFile(sidecarName) ?: return null
        val config = PiperVoiceConfig.parse(sidecarText) ?: return null
        return PiperVoiceEntry(onnxFile.removeSuffix(ONNX_SUFFIX), onnxFile, sidecarName, config)
    }

    private fun forcedVoiceEntry(
        bundle: ModelBundle,
        onnxFile: String,
    ): PiperVoiceEntry {
        val sidecarName = onnxFile + SIDECAR_EXTRA_SUFFIX
        val config = bundle.sideFile(sidecarName)?.let(PiperVoiceConfig::parse) ?: PiperVoiceConfig.fallback()
        return PiperVoiceEntry(onnxFile.removeSuffix(ONNX_SUFFIX), onnxFile, sidecarName, config)
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        entries: List<PiperVoiceEntry>,
        origin: Origin,
    ): ModelDescriptor {
        val assetPaths = mutableMapOf<String, String>()
        val voices = entries.map { entry -> entry.toVoice(bundle.rootPath, assetPaths) }
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = descriptorDisplayName(bundle, entries),
            origin = origin,
            sampleRate = entries.first().config.sampleRate,
            voices = voices,
            speedRange = SPEED_RANGE,
            defaultVoiceId = voices.first().id,
            defaultSpeed = DEFAULT_SPEED,
            assetPaths = assetPaths,
        )
    }

    private fun descriptorDisplayName(
        bundle: ModelBundle,
        entries: List<PiperVoiceEntry>,
    ): String = if (entries.size == 1) "Piper - ${entries.first().voiceId}" else "Piper - ${bundle.id}"

    private fun PiperVoiceEntry.toVoice(
        rootPath: String?,
        assetPaths: MutableMap<String, String>,
    ): Voice {
        assetPaths[assetKey(voiceId, ONNX_SUFFIX)] = pathFor(rootPath, onnxFile)
        assetPaths[assetKey(voiceId, SIDECAR_SUFFIX)] = pathFor(rootPath, sidecarFile)
        return Voice(id = voiceId, name = prettify(voiceId), language = config.language)
    }

    private fun pathFor(
        rootPath: String?,
        fileName: String,
    ): String = if (rootPath != null) "$rootPath/$fileName" else fileName

    private fun prettify(voiceId: String): String = voiceId.replace('_', ' ').replace('-', ' ')

    private fun assetKey(
        voiceId: String,
        suffix: String,
    ): String = voiceId + suffix

    companion object {
        const val ENGINE_ID = "piper"

        private const val ONNX_SUFFIX = ".onnx"
        private const val SIDECAR_SUFFIX = ".onnx.json"
        private const val SIDECAR_EXTRA_SUFFIX = ".json"
        private const val ONNX_RUNTIME_ID = "onnx"

        private const val INPUT_TENSOR = "input"
        private const val INPUT_LENGTHS_TENSOR = "input_lengths"
        private const val SCALES_TENSOR = "scales"
        private const val OUTPUT_TENSOR = "output"
        private const val SCALES_SIZE = 3

        private val SPEED_RANGE = 0.5f..2.0f
        private const val DEFAULT_SPEED = 1.0f
    }
}

private data class LoadedVoice(
    val session: InferenceSession,
    val config: PiperVoiceConfig,
)

private data class PiperVoiceEntry(
    val voiceId: String,
    val onnxFile: String,
    val sidecarFile: String,
    val config: PiperVoiceConfig,
)

private fun readSidecarFile(path: String): String? {
    val file = File(path)
    if (!file.exists()) return null
    return file.readText()
}
