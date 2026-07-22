package com.phonetts.engines.piper

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
import com.phonetts.core.runtime.Runtime
import com.phonetts.core.runtime.Tensor
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.closeAllQuietly
import com.phonetts.engines.common.floatsOrError
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.openWithRollback
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
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
 * voice graphs one Piper bundle may contain). That per-voice-N-sessions topology is bespoke to
 * Piper, so `load()`/`unload()` are hand-written here (see [AbstractVoiceEngine]'s KDoc) using
 * only the leaf session-lifecycle helpers from `com.phonetts.engines.common`.
 *
 * [sidecarReader] is injected so tests can supply sidecar JSON without touching a real
 * filesystem; production uses the real-file default.
 */
internal class PiperEngine(
    context: EngineContext,
    private val sidecarReader: (path: String) -> String? = ::readSidecarFile,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = "Piper"
    override val engineLabel: String = displayName

    private var loadedVoices: Map<String, LoadedVoice> = emptyMap()

    // The UNIQUE session per source .onnx graph. A multi-speaker graph backs many [LoadedVoice]s
    // (one per speaker) but is loaded ONCE — a 4 GB phone cannot hold 100+ copies of a VCTK graph
    // (spec rule 6). Kept separately from [loadedVoices] so unload() closes each graph exactly once.
    private var loadedSessions: List<InferenceSession> = emptyList()
    private var loadedDescriptor: ModelDescriptor? = null

    override fun isLoaded(): Boolean = loadedVoices.isNotEmpty()

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val onnxFiles = bundle.fileNames.filter { it.endsWith(ONNX_SUFFIX) }
        val entries = onnxFiles.mapNotNull { onnxFile -> validVoiceEntry(bundle, onnxFile) }
        val resolved = entries.ifEmpty { listOfNotNull(singleOnnxConfigJsonEntry(bundle, onnxFiles)) }
        if (resolved.isEmpty()) return null
        return EngineMatch(id, buildDescriptor(bundle, resolved, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val onnxFiles = bundle.fileNames.filter { it.endsWith(ONNX_SUFFIX) }
        require(onnxFiles.isNotEmpty()) {
            "Piper forcedMatch requires at least one .onnx file in bundle '${bundle.id}'"
        }
        val entries = onnxFiles.map { onnxFile -> forcedVoiceEntry(bundle, onnxFile, onnxFiles.size) }
        return EngineMatch(id, buildDescriptor(bundle, entries, Origin.SIDELOADED))
    }

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, ONNX_RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        unload()
        val runtime = requireRuntime(context, ONNX_RUNTIME_ID, engineLabel)
        val loaded =
            openWithRollback { opened ->
                // One session per source .onnx graph (single- OR multi-speaker), created once.
                val files =
                    sourceFileBases(descriptor).associateWith { base ->
                        val file = loadFile(descriptor, base, runtime)
                        opened.add(file.session)
                        file
                    }
                // Map every public voice — a whole single-speaker graph, or one speaker of a
                // multi-speaker graph — onto its (shared) session plus the sid to feed, if any.
                val voices = descriptor.voices.associate { voice -> voice.id to resolveLoadedVoice(voice.id, files) }
                LoadedState(voices, files.values.map { it.session })
            }
        loadedVoices = loaded.voices
        loadedSessions = loaded.sessions
        loadedDescriptor = descriptor
    }

    override fun unload() {
        closeAllQuietly(loadedSessions)
        loadedVoices = emptyMap()
        loadedSessions = emptyList()
        loadedDescriptor = null
    }

    override fun voices(): List<Voice> = loadedDescriptor?.voices ?: emptyList()

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val speed = params.speed
        val loadedVoice = loadedVoices[voiceId] ?: error("Piper voice '$voiceId' is not loaded")
        val frontend = PiperFrontend(context.phonemizer, loadedVoice.config.phonemeIdMap)
        val input = frontend.toModelInput(sentence, loadedVoice.config.language)
        // Speed ALWAYS routes to the model's native length_scale (spec rule 2) — never resample
        // output audio. length_scale is INVERSE to speed: larger = slower. Anchor on the voice's
        // own config default (normally 1.0) so speed=1.0 reproduces the model's natural pace.
        val lengthScale = loadedVoice.config.defaultLengthScale / speed
        val outputs = loadedVoice.session.run(sessionInputs(input.tokenIds, loadedVoice, lengthScale))
        return outputs.floatsOrError(OUTPUT_TENSOR, engineLabel)
    }

    // ASSUMPTION (not runnable against a real ONNX graph in this environment): mirrors the
    // upstream piper1-gpl VITS export signature — "input" (int64 phoneme ids, [1, T]),
    // "input_lengths" (int64 [1]), "scales" (float32 [noise_scale, length_scale, noise_w]). A
    // MULTI-speaker graph adds a required "sid" (int64 [1]) input; a single-speaker graph has no
    // such input, so the sid tensor is added ONLY when this voice carries a speaker id — feeding a
    // spurious "sid" to a single-speaker graph would be rejected just as omitting it broke the
    // multi-speaker graphs.
    private fun sessionInputs(
        tokenIds: LongArray,
        loadedVoice: LoadedVoice,
        lengthScale: Float,
    ): Map<String, Tensor> {
        val inputs =
            mutableMapOf(
                INPUT_TENSOR to Tensor.longs(tokenIds, intArrayOf(1, tokenIds.size)),
                INPUT_LENGTHS_TENSOR to Tensor.longs(longArrayOf(tokenIds.size.toLong())),
                SCALES_TENSOR to
                    Tensor.floats(
                        floatArrayOf(loadedVoice.config.noiseScale, lengthScale, loadedVoice.config.noiseW),
                        intArrayOf(SCALES_SIZE),
                    ),
            )
        loadedVoice.speakerId?.let { sid -> inputs[SID_TENSOR] = Tensor.longs(longArrayOf(sid.toLong())) }
        return inputs
    }

    /** Distinct source-graph base names (`<base>.onnx`), each of which becomes exactly one session. */
    private fun sourceFileBases(descriptor: ModelDescriptor): List<String> =
        descriptor.assetPaths.keys
            .filter { it.endsWith(ONNX_SUFFIX) }
            .map { it.removeSuffix(ONNX_SUFFIX) }
            .distinct()

    private fun loadFile(
        descriptor: ModelDescriptor,
        base: String,
        runtime: Runtime,
    ): LoadedFile {
        val onnxPath = requireAssetPath(descriptor, assetKey(base, ONNX_SUFFIX), engineLabel)
        val configPath = descriptor.assetPaths[assetKey(base, SIDECAR_SUFFIX)]
        val config = configPath?.let(sidecarReader)?.let(PiperVoiceConfig::parse) ?: PiperVoiceConfig.fallback()
        return LoadedFile(runtime.createSession(onnxPath), config)
    }

    // A public voice id is either a whole single-speaker graph (id == the graph's base name) or one
    // speaker of a multi-speaker graph (id == "<base>#<speaker name>"). Resolve it to that graph's
    // shared session plus the sid to feed (null for single-speaker).
    private fun resolveLoadedVoice(
        voiceId: String,
        files: Map<String, LoadedFile>,
    ): LoadedVoice {
        files[voiceId]?.let { return LoadedVoice(it.session, it.config, speakerId = null) }
        val base = voiceId.substringBefore(SPEAKER_SEPARATOR)
        val file = files[base] ?: error("Piper voice '$voiceId' has no source graph in the loaded bundle")
        val speakerName = voiceId.substringAfter(SPEAKER_SEPARATOR)
        val speaker =
            file.config.speakers().firstOrNull { it.name == speakerName }
                ?: error("Piper voice '$voiceId' names unknown speaker '$speakerName'")
        return LoadedVoice(file.session, file.config, speakerId = speaker.sid)
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

    // issue #95: many valid Piper repos (speaches-ai/*, ufozone/*, Lucasllfs/Razo-piper-voice) ship
    // the exact same sidecar shape but name it plain "config.json" instead of "<voice>.onnx.json".
    // Only accepted when the bundle has EXACTLY ONE .onnx — with two or more graphs a single
    // "config.json" is ambiguous about which one it pairs with, so it is never guessed at (rule 4).
    // Content still has to pass [PiperVoiceConfig.parse]'s fail-closed gate, so a foreign
    // "config.json" (a different model family entirely) is still rejected. Deliberately out of
    // scope: `ayousanz/piper-plus-*`, whose "config.json" is Piper-shaped but the graph needs extra
    // language_id/prosody inputs this engine does not feed.
    private fun singleOnnxConfigJsonEntry(
        bundle: ModelBundle,
        onnxFiles: List<String>,
    ): PiperVoiceEntry? {
        val onnxFile = onnxFiles.singleOrNull() ?: return null
        if (!bundle.hasFile(CONFIG_JSON_NAME)) return null
        val configText = bundle.sideFile(CONFIG_JSON_NAME) ?: return null
        val config = PiperVoiceConfig.parse(configText) ?: return null
        return PiperVoiceEntry(onnxFile.removeSuffix(ONNX_SUFFIX), onnxFile, CONFIG_JSON_NAME, config)
    }

    private fun forcedVoiceEntry(
        bundle: ModelBundle,
        onnxFile: String,
        onnxFileCount: Int,
    ): PiperVoiceEntry {
        val sidecarName = onnxFile + SIDECAR_EXTRA_SUFFIX
        val stemConfig = bundle.sideFile(sidecarName)?.let(PiperVoiceConfig::parse)
        if (stemConfig != null) {
            return PiperVoiceEntry(onnxFile.removeSuffix(ONNX_SUFFIX), onnxFile, sidecarName, stemConfig)
        }
        // Same single-onnx "config.json" fallback as inspect() (issue #95), kept consistent so a
        // forced sideload of one of these repos also records the real sidecar path load() reads,
        // instead of silently landing on family defaults it didn't need to.
        val configEntry = if (onnxFileCount == 1) singleOnnxConfigJsonEntry(bundle, listOf(onnxFile)) else null
        if (configEntry != null) return configEntry
        return PiperVoiceEntry(onnxFile.removeSuffix(ONNX_SUFFIX), onnxFile, sidecarName, PiperVoiceConfig.fallback())
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        entries: List<PiperVoiceEntry>,
        origin: Origin,
    ): ModelDescriptor {
        val assetPaths = mutableMapOf<String, String>()
        // A single-speaker .onnx becomes one voice; a multi-speaker .onnx fans out into one voice
        // per speaker (all sharing that one graph) — voices discovered from the model (rule 1).
        val voices = entries.flatMap { entry -> entry.toVoices(bundle.rootPath, assetPaths) }
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = if (entries.size == 1) "Piper - ${entries.first().voiceId}" else "Piper - ${bundle.id}",
            origin = origin,
            sampleRate = entries.first().config.sampleRate,
            voices = voices,
            defaultVoiceId = voices.first().id,
            // Introspected: Piper's VITS graph has a native length/duration input (scales[1]), so it
            // advertises a speed knob (routed to length_scale — never resampled, CLAUDE.md rule 2).
            parameters = listOf(ModelParameter.speed(SPEED_RANGE, DEFAULT_SPEED)),
            assetPaths = assetPaths,
            // Approximate peak-RAM estimate (issue #38): a small VITS graph, scaled by how many voice
            // graphs this bundle loads at once. A-priori only — refined by observed peak RAM.
            resourceCost = ResourceCost.peakRamMebibytes(PEAK_RAM_MIB_PER_VOICE * entries.size),
        )
    }

    // One .onnx graph -> its asset paths (keyed by the graph's base name) + its public voice(s):
    // the whole graph for a single-speaker voice, or one voice per speaker for a multi-speaker one.
    private fun PiperVoiceEntry.toVoices(
        rootPath: String?,
        assetPaths: MutableMap<String, String>,
    ): List<Voice> {
        assetPaths[assetKey(voiceId, ONNX_SUFFIX)] = joinAssetPath(rootPath, onnxFile)
        assetPaths[assetKey(voiceId, SIDECAR_SUFFIX)] = joinAssetPath(rootPath, sidecarFile)
        val speakers = config.speakers()
        if (speakers.isEmpty()) {
            return listOf(Voice(id = voiceId, name = prettify(voiceId), language = config.language))
        }
        return speakers.map { speaker ->
            Voice(
                id = "$voiceId$SPEAKER_SEPARATOR${speaker.name}",
                name = "${prettify(voiceId)} — ${speaker.name}",
                language = config.language,
            )
        }
    }

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

        // issue #95: the plain "config.json" name several valid Piper repos use instead of
        // "<voice>.onnx.json" — only ever tried for a single-.onnx bundle, see
        // [singleOnnxConfigJsonEntry].
        private const val CONFIG_JSON_NAME = "config.json"
        private const val ONNX_RUNTIME_ID = "onnx"

        private const val INPUT_TENSOR = "input"
        private const val INPUT_LENGTHS_TENSOR = "input_lengths"
        private const val SCALES_TENSOR = "scales"
        private const val SID_TENSOR = "sid"
        private const val OUTPUT_TENSOR = "output"
        private const val SCALES_SIZE = 3

        // Separates a multi-speaker graph's base name from a speaker name in a public voice id,
        // e.g. "en_US-libritts_r-medium#p123". Chosen because it cannot occur in a Piper filename.
        private const val SPEAKER_SEPARATOR = "#"

        private val SPEED_RANGE = 0.5f..2.0f
        private const val DEFAULT_SPEED = 1.0f

        // Approximate peak resident RAM (MiB) per loaded VITS voice graph.
        private const val PEAK_RAM_MIB_PER_VOICE = 120L
    }
}

// One loaded source graph: its session and its parsed sidecar. Shared by every speaker-voice of a
// multi-speaker graph.
private data class LoadedFile(
    val session: InferenceSession,
    val config: PiperVoiceConfig,
)

// A public voice bound to the (possibly shared) session it runs on, plus the sid to feed — null for
// a single-speaker graph, an integer speaker id for one speaker of a multi-speaker graph.
private data class LoadedVoice(
    val session: InferenceSession,
    val config: PiperVoiceConfig,
    val speakerId: Int?,
)

// The outcome of a load(): every public voice mapped to its (shared) session, plus the distinct
// sessions to close on unload().
private data class LoadedState(
    val voices: Map<String, LoadedVoice>,
    val sessions: List<InferenceSession>,
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
