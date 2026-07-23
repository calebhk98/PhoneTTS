package com.phonetts.engines.kittentts

import com.phonetts.core.engine.BlendableVoices
import com.phonetts.core.engine.BlendedVoiceSpec
import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.engine.VoiceBlend
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
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime
import com.phonetts.engines.common.sideFileContainsAnyMarker
import com.phonetts.engines.common.sideFileContainsMarker
import java.io.File

/**
 * KittenTTS (KittenML/kitten-tts-nano-0.1) engine. VALIDATED (docs/research/onnx-io.md): the
 * real model is StyleTTS2, shape-identical to Kokoro — its ONNX graph takes `input_ids`,
 * a 256-dim `style` voice embedding, and `speed`, **not** an integer `speaker_id`. There is no
 * per-voice weight file; the 8 built-in voices are 256-dim style embedding rows in `voices.npz`,
 * selected by name at synthesis time exactly like [com.phonetts.engines.kokoro.KokoroEngine].
 *
 * [inspect] recognizes TWO real companion layouts (spec §6.2 / §9.1 -- fail closed), both keyed on
 * a `.onnx` weights file:
 *  - **`voices.npz` layout** (KittenML `kitten-tts-nano-0.1/0.2`, and the onnx-community v0.8
 *    conversions): a config side file mentioning [CONFIG_MARKER] -- checked in
 *    [CONFIG_FILE_CANDIDATES] order, [KITTEN_CONFIG_FILE] first, since the onnx-community exports
 *    put it in a sibling `kitten_config.json` and leave `config.json` generic -- plus a
 *    [VOICES_FILE] (`voices.npz`) present by name. Whichever file carries the marker is recorded
 *    under [CONFIG_ASSET_KEY] so [doLoad] reads the right bytes.
 *  - **`voices/<name>.bin` layout** (`onnx-community/kitten-tts-nano-0.1-ONNX`, issue #111): the
 *    SAME `style_text_to_speech_2` config + tokenizer + `voices/<name>.bin` files Kokoro ships,
 *    distinguished ONLY by the KittenTTS `expr-voice-*` voice-name signature ([KITTEN_VOICE_PREFIX]).
 *    Without that discriminator Kokoro would claim it and mislabel it "Kokoro".
 *
 * [ModelBundle] only carries small *text* side files for fingerprinting (`DirectoryBundleReader`
 * excludes `.npz`/`.bin` weight-shaped binaries), so `inspect()` confirms the voice files exist by
 * name only; the real per-voice rows are decoded later by [KittenVoiceTable] (npz) or
 * [KittenVoiceBinReader] (bin) once [load] has bytes.
 *
 * **v0.8 fail-closed (issue #110):** the onnx-community v0.8 exports declare `"type": "ONNX2"` in
 * `kitten_config.json` -- a graph contract that differs from the verified v0.1 signature
 * [synthesizeSentence] hard-codes. Claiming it would only crash at `run()`, so both [inspect] and
 * [doLoad] fail closed on the [UNSUPPORTED_CONTRACT_MARKER] rather than claim-then-crash.
 */
internal class KittenEngine(
    context: EngineContext,
    // Injected so load() stays plain-JVM testable without real files on disk (parity with
    // KokoroEngine's fileReader seam) -- here reading bytes rather than text, since voices.npz
    // is binary.
    private val fileReader: (path: String) -> ByteArray = { File(it).readBytes() },
) : AbstractVoiceEngine(context),
    BlendableVoices {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private val frontend = KittenFrontend(context.phonemizer)
    private var session: InferenceSession? = null
    private var voiceEmbeddings: Map<String, FloatArray> = emptyMap()
    private var loadedVoices: List<Voice> = emptyList()

    override fun isLoaded(): Boolean = session != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val modelFile = bundle.fileNames.firstOrNull { it.endsWith(MODEL_SUFFIX) } ?: return null
        return inspectNpzLayout(bundle, modelFile) ?: inspectBinLayout(bundle, modelFile)
    }

    /**
     * The `voices.npz` layout (KittenML `kitten-tts-nano-0.1/0.2`, `onnx-community` v0.8): a
     * `kitten_tts`-marked config side file plus a `voices.npz`. Fails closed on the v0.8 `ONNX2`
     * graph contract (issue #110): that graph differs from the verified v0.1 signature this
     * engine's [synthesizeSentence] hard-codes, so claiming it would only crash at `run()`.
     */
    private fun inspectNpzLayout(
        bundle: ModelBundle,
        modelFile: String,
    ): EngineMatch? {
        val configFile = markedConfigFile(bundle, KITTEN_MARKERS) ?: return null
        if (!bundle.hasFile(VOICES_FILE)) return null
        if (bundle.sideFileContainsMarker(configFile, UNSUPPORTED_CONTRACT_MARKER)) return null

        val voicesAsset = VOICES_ASSET_KEY to joinAssetPath(bundle, VOICES_FILE)
        return EngineMatch(
            id,
            buildDescriptor(bundle, modelFile, configFile, VOICE_NAMES, Origin.BUILT_IN, voicesAsset),
        )
    }

    /**
     * The `voices/<name>.bin` layout (`onnx-community/kitten-tts-nano-0.1-ONNX`, issue #111): the
     * same StyleTTS2 marker + `voices/<name>.bin` layout Kokoro uses, distinguished ONLY by its
     * KittenTTS `expr-voice-*` voice-name signature. That signature is what makes this KittenTTS's
     * and not Kokoro's; a bundle whose `voices/<name>.bin` files are not entirely the kitten set is
     * refused (fail closed, spec §9.1 rule 4).
     */
    private fun inspectBinLayout(
        bundle: ModelBundle,
        modelFile: String,
    ): EngineMatch? {
        val configFile = markedConfigFile(bundle, STYLE_MARKERS) ?: return null
        val voiceIds = kittenBinVoiceIds(bundle) ?: return null

        val voicesAsset = VOICES_DIR_ASSET to joinAssetPath(bundle, VOICES_DIR)
        return EngineMatch(id, buildDescriptor(bundle, modelFile, configFile, voiceIds, Origin.BUILT_IN, voicesAsset))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val modelFile = bundle.fileNames.firstOrNull { it.endsWith(MODEL_SUFFIX) }
        // A bundle with no weights is a bad ARGUMENT (unusable input), not bad object state --
        // hence require/IllegalArgumentException, consistent with the other engines' forcedMatch.
        requireNotNull(modelFile) { "bundle '${bundle.id}' has no $MODEL_SUFFIX weights file; KittenTTS cannot run it" }

        // The user's manual assignment is authoritative and never refuses -- but still prefer
        // whichever config file actually carries a marker (same fingerprint as inspect()) so
        // doLoad() reads the right side file; fall back to CONFIG_FILE when none is present.
        val configFile = markedConfigFile(bundle, STYLE_MARKERS) ?: CONFIG_FILE
        val binVoiceIds = kittenBinVoiceIds(bundle)
        return when {
            // voices.npz present: honor the real 8-voice table by name (spec §6.2 family defaults).
            bundle.hasFile(VOICES_FILE) ->
                EngineMatch(
                    id,
                    buildDescriptor(
                        bundle,
                        modelFile,
                        configFile,
                        VOICE_NAMES,
                        Origin.SIDELOADED,
                        VOICES_ASSET_KEY to joinAssetPath(bundle, VOICES_FILE),
                    ),
                )
            // voices/<name>.bin present: honor the discovered per-file voices (v0.1 onnx-community).
            binVoiceIds != null ->
                EngineMatch(
                    id,
                    buildDescriptor(
                        bundle,
                        modelFile,
                        configFile,
                        binVoiceIds,
                        Origin.SIDELOADED,
                        VOICES_DIR_ASSET to joinAssetPath(bundle, VOICES_DIR),
                    ),
                )
            // No voice table at all: supply a single generic voice so the dropdowns render.
            else ->
                EngineMatch(
                    id,
                    buildDescriptor(bundle, modelFile, configFile, DEFAULT_FAMILY_VOICES, Origin.SIDELOADED),
                )
        }
    }

    /**
     * Returns whichever of [CONFIG_FILE_CANDIDATES] carries any of [markers] in [bundle], checking
     * [KITTEN_CONFIG_FILE] first since the genuine `onnx-community` ONNX conversions put their
     * marker there and leave [CONFIG_FILE] generic. `null` means no side file mentions a marker --
     * fail closed, never guess (spec §9.1 rule 4).
     */
    private fun markedConfigFile(
        bundle: ModelBundle,
        markers: Collection<String>,
    ): String? =
        CONFIG_FILE_CANDIDATES.firstOrNull {
                candidate ->
            bundle.sideFileContainsAnyMarker(candidate, markers)
        }

    /**
     * The KittenTTS `expr-voice-*` voice ids discovered from `voices/<name>.bin` files, or null
     * when there are none OR when any `voices/<name>.bin` is NOT that kitten signature (so a Kokoro
     * `voices/af_*.bin` table is never mistaken for KittenTTS's).
     */
    private fun kittenBinVoiceIds(bundle: ModelBundle): List<String>? {
        val ids =
            bundle.fileNames
                .filter { it.startsWith(VOICES_DIR_PREFIX) && it.endsWith(KittenVoiceBinReader.BIN_SUFFIX) }
                .map { it.removePrefix(VOICES_DIR_PREFIX).removeSuffix(KittenVoiceBinReader.BIN_SUFFIX) }
                .sorted()
        if (ids.isEmpty() || ids.any { !it.startsWith(KITTEN_VOICE_PREFIX) }) return null
        return ids
    }

    override fun isRuntimeAvailable(): Boolean = requireRuntime(context, RUNTIME_ID, engineLabel).isAvailable()

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        val runtime = requireRuntime(context, RUNTIME_ID, engineLabel)
        val modelPath = requireAssetPath(descriptor, MODEL_ASSET_KEY, engineLabel)
        requireSupportedContract(descriptor)

        session?.close()
        session = runtime.createSession(modelPath)
        loadVoiceTable(descriptor)
    }

    /**
     * Load-time backstop for the v0.8 crash (issue #110): auto-detection already refuses an `ONNX2`
     * bundle, but a user who force-assigns KittenTTS to one bypasses [inspect]. Re-check the config
     * bytes here and fail with a clear message rather than letting the mismatched graph crash at
     * `run()`. Unreadable config (e.g. a bare sideloaded `.onnx`) is skipped -- nothing to gate on.
     */
    private fun requireSupportedContract(descriptor: ModelDescriptor) {
        val configPath = descriptor.assetPaths[CONFIG_ASSET_KEY] ?: return
        val text = runCatching { String(fileReader(configPath), Charsets.UTF_8) }.getOrNull() ?: return
        if (!text.contains(UNSUPPORTED_CONTRACT_MARKER, ignoreCase = true)) return
        error(
            "$engineLabel cannot run this variant: its config declares the '$UNSUPPORTED_CONTRACT_MARKER' " +
                "graph contract, which differs from the verified v0.1 graph this engine implements",
        )
    }

    override fun unload() {
        closeAllQuietly(session)
        session = null
        voiceEmbeddings = emptyMap()
        loadedVoices = emptyList()
    }

    override fun voices(): List<Voice> = loadedVoices

    /**
     * Voice mixing (issue #42): KittenTTS's StyleTTS2 graph feeds a continuous 256-dim `style`
     * vector, so an in-between voice is just the [VoiceBlend] of two loaded embeddings — registered
     * as a new selectable voice that synthesis then feeds like any other. Fails closed if either
     * source id isn't loaded.
     */
    override fun addBlendedVoice(spec: BlendedVoiceSpec): Voice? {
        val a = voiceEmbeddings[spec.voiceAId] ?: return null
        val b = voiceEmbeddings[spec.voiceBId] ?: return null
        val voice = Voice(id = spec.id, name = spec.name, language = LANGUAGE)
        voiceEmbeddings = voiceEmbeddings + (spec.id to VoiceBlend.blend(a, b, spec.weight))
        loadedVoices = loadedVoices.filterNot { it.id == spec.id } + voice
        return voice
    }

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val speed = params.speed
        val activeSession = session ?: error("$engineLabel.synthesizeSentence called before load()")
        val embedding = voiceEmbeddings[voiceId] ?: error("Unknown KittenTTS voice id '$voiceId'")

        val modelInput = frontend.toModelInput(sentence, LANGUAGE)
        // VALIDATED against KittenML/kitten-tts-nano-0.1 (inspected 2026-07, docs/research/onnx-io.md):
        // the graph's inputs are "input_ids" int64 [1, T], "style" float32 [1, 256] (the selected
        // voice's embedding row), "speed" float32 [1]; the audio output is "waveform" float32 [samples]
        // (a second "duration" output exists but is ignored).
        val inputs =
            mapOf(
                INPUT_IDS_KEY to Tensor.longs(modelInput.tokenIds, intArrayOf(1, modelInput.tokenIds.size)),
                STYLE_KEY to Tensor.floats(embedding, intArrayOf(1, embedding.size)),
                SPEED_KEY to Tensor.scalarFloat(speed),
            )
        val outputs = activeSession.run(inputs)
        return outputs.floatsOrError(WAVEFORM_KEY, engineLabel)
    }

    private fun loadVoiceTable(descriptor: ModelDescriptor) {
        val entries = readVoiceEntries(descriptor)

        if (entries.isEmpty()) {
            // Sideloaded/forced-match bundle with no real voices.npz on disk: fall back to a
            // single zero-vector "default" style so synthesize() still has something to feed the
            // graph. Real voice quality for such bundles is out of scope for this seam (spec TDD
            // note: "test the plumbing, not the audio").
            voiceEmbeddings = mapOf(descriptor.defaultVoiceId to FloatArray(FALLBACK_EMBEDDING_SIZE))
            loadedVoices = descriptor.voices
            return
        }

        voiceEmbeddings = entries.associate { it.voice.id to it.embedding }
        loadedVoices = entries.map { it.voice }
    }

    /**
     * Reads voice embeddings from whichever layout the descriptor recorded: the zipped `voices.npz`
     * ([VOICES_ASSET_KEY]) or the per-file `voices/<name>.bin` directory ([VOICES_DIR_ASSET]).
     * Empty when neither is present (a forced-match bare `.onnx`), which drives the fallback above.
     */
    private fun readVoiceEntries(descriptor: ModelDescriptor): List<KittenVoiceTable.Entry> {
        descriptor.assetPaths[VOICES_ASSET_KEY]?.let { return KittenVoiceTable.parse(fileReader(it)) }
        descriptor.assetPaths[VOICES_DIR_ASSET]?.let {
            return KittenVoiceBinReader.readVoices(it, descriptor.voices, fileReader)
        }
        return emptyList()
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        modelFile: String,
        configFile: String,
        voiceNames: List<String>,
        origin: Origin,
        voicesAsset: Pair<String, String>? = null,
    ): ModelDescriptor {
        val voices = voiceNames.map { name -> Voice(id = name, name = name, language = LANGUAGE) }
        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = origin,
            // 24000 Hz is fixed across all KittenTTS variants (docs/research/model-facts.md,
            // verified) -- this is the descriptor layer, the one sanctioned place for it (spec
            // §5.7); nothing outside this engine may repeat this literal.
            sampleRate = SAMPLE_RATE,
            voices = voices,
            defaultVoiceId = voices.first().id,
            // Introspected: KittenTTS's StyleTTS2 graph has a native "speed" input, so it advertises
            // a speed knob (routed to that scalar — never resampled, CLAUDE.md rule 2).
            parameters = listOf(ModelParameter.speed(SPEED_RANGE, DEFAULT_SPEED)),
            // Introspected: the StyleTTS2 graph takes a continuous 256-dim `style` vector, so two
            // voices can be linearly interpolated into an in-between one (issue #42). A descriptor
            // fact the "mix voices" UI derives from — never a model-name special case (rule 5).
            supportsVoiceBlend = true,
            // The voices asset key varies by layout (voices.npz vs a voices/ dir), so it is passed
            // in by whichever fingerprint matched rather than hardcoded to one file here.
            assetPaths =
                buildMap {
                    put(MODEL_ASSET_KEY, joinAssetPath(bundle, modelFile))
                    put(CONFIG_ASSET_KEY, joinAssetPath(bundle, configFile))
                    voicesAsset?.let { (key, path) -> put(key, path) }
                },
            // Approximate peak-RAM estimate (issue #38): KittenTTS is the tiny ~15M-param model, the
            // lightest engine here. A-priori only — refined by observed peak RAM.
            resourceCost = ResourceCost.peakRamMebibytes(PEAK_RAM_MIB),
        )
    }

    companion object {
        const val ENGINE_ID = "kittentts"
        const val DISPLAY_NAME = "KittenTTS"

        /** id of the [com.phonetts.core.runtime.Runtime] this engine runs on (spec: ONNX for KittenTTS). */
        const val RUNTIME_ID = "onnx"

        const val SAMPLE_RATE = 24_000

        // Approximate peak resident RAM (MiB) while loaded + generating — the lightest model here.
        private const val PEAK_RAM_MIB = 80L

        // VALIDATED (docs/research/onnx-io.md): the ONNX graph takes a native "speed" scalar, same
        // family/range convention as the sibling ONNX engines.
        val SPEED_RANGE = 0.5f..1.5f
        const val DEFAULT_SPEED = 1.0f

        // English-only dev-preview as of v0.8 (docs/research/model-facts.md) -- ASSUMPTION that
        // may need to become descriptor-driven if upstream adds languages.
        const val LANGUAGE = "en"

        const val MODEL_SUFFIX = ".onnx"
        const val CONFIG_FILE = "config.json"

        // The genuine `onnx-community/KittenTTS-{Nano,Mini,Micro}-v0.8-ONNX` conversions put the
        // `kitten_tts` marker in this sibling file instead of CONFIG_FILE (issue #96) -- their
        // config.json only says `{"model_type":"style_text_to_speech_2"}`.
        const val KITTEN_CONFIG_FILE = "kitten_config.json"

        // Checked in order by markedConfigFile(): KITTEN_CONFIG_FILE first (the genuine ONNX
        // conversions), CONFIG_FILE as the fallback (other bundles that embed the marker directly).
        private val CONFIG_FILE_CANDIDATES = listOf(KITTEN_CONFIG_FILE, CONFIG_FILE)

        // VALIDATED (docs/research/onnx-io.md): the real voice table is a `voices.npz` binary
        // archive, not a `voices.json` name array.
        const val VOICES_FILE = "voices.npz"

        // The OTHER real layout: `onnx-community/kitten-tts-nano-0.1-ONNX` ships per-voice
        // `voices/<name>.bin` files instead of a single voices.npz (issue #111).
        const val VOICES_DIR = "voices"
        private const val VOICES_DIR_PREFIX = "$VOICES_DIR/"

        // KittenTTS's own marker; the generic StyleTTS2 marker the onnx-community exports share with
        // Kokoro. The bin layout is distinguished from Kokoro's identical file layout by the kitten
        // voice-name signature ([KITTEN_VOICE_PREFIX]), not by these markers alone.
        private const val CONFIG_MARKER = "kitten_tts"
        private const val STYLE_TEXT_TO_SPEECH_MARKER = "style_text_to_speech_2"
        private val KITTEN_MARKERS = listOf(CONFIG_MARKER)
        private val STYLE_MARKERS = listOf(CONFIG_MARKER, STYLE_TEXT_TO_SPEECH_MARKER)

        // The v0.8 conversions' `kitten_config.json` declares `"type": "ONNX2"` -- a graph contract
        // that differs from the verified v0.1 signature [synthesizeSentence] implements. Its presence
        // makes inspect()/load() fail closed rather than claim-then-crash (issue #110).
        private const val UNSUPPORTED_CONTRACT_MARKER = "ONNX2"

        // The prefix every KittenTTS voice id shares (`expr-voice-2-m`, ...). A Kokoro voice table
        // never uses it, so it is the one signal separating the two identical StyleTTS2 bin layouts.
        private const val KITTEN_VOICE_PREFIX = "expr-voice-"

        const val MODEL_ASSET_KEY = "model"
        const val CONFIG_ASSET_KEY = "config"
        const val VOICES_ASSET_KEY = "voices"
        const val VOICES_DIR_ASSET = "voicesDir"

        // VALIDATED (docs/research/onnx-io.md) ONNX graph tensor names (KittenML/kitten-tts-nano-0.1).
        const val INPUT_IDS_KEY = "input_ids"
        const val STYLE_KEY = "style"
        const val SPEED_KEY = "speed"
        const val WAVEFORM_KEY = "waveform"

        private const val FALLBACK_EMBEDDING_SIZE = 1

        // VALIDATED (docs/research/onnx-io.md): the 8 named style embeddings voices.npz ships,
        // one row per `.npy` entry (name minus the `.npy` suffix).
        val VOICE_NAMES =
            listOf(
                "expr-voice-2-m",
                "expr-voice-2-f",
                "expr-voice-3-m",
                "expr-voice-3-f",
                "expr-voice-4-m",
                "expr-voice-4-f",
                "expr-voice-5-m",
                "expr-voice-5-f",
            )

        /** Family default used by [forcedMatch] when no real voices.npz is present. */
        private val DEFAULT_FAMILY_VOICES = listOf("Default")
    }
}
