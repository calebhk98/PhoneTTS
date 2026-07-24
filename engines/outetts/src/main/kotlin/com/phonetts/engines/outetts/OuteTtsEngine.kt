package com.phonetts.engines.outetts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.model.ResourceCost
import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.runtime.NativeTtsRuntime
import com.phonetts.core.runtime.NativeTtsSession
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.json.JsonValue
import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime

/**
 * OuteTTS (OuteAI) - an autoregressive LLaMA/Qwen-style token-LM shipped as GGUF, with a
 * SEPARATE audio-decoder GGUF (see `docs/research/outetts-facts.md` §3): early releases (v0.1-
 * v0.3) decode with WavTokenizer, the current v1.0 line (`OuteTTS-1.0-0.6B-GGUF`,
 * `Llama-OuteTTS-1.0-1B-GGUF`) with DAC. Like [com.phonetts.engines.cosyvoice2.CosyVoice2Engine]
 * and [com.phonetts.engines.ggmltts.GgmlTtsEngine] (its closest templates), this is a thin
 * delegate over the non-ONNX [NativeTtsRuntime] seam (id [NATIVE_RUNTIME_ID], `"outetts"`): the
 * native side owns the ENTIRE text→speech-token→audio pipeline in one call, so there is
 * deliberately no Kotlin `TextFrontend` here either - OuteTTS's own text normalization and word
 * alignment live inside the LLM's prompt construction (facts doc §2), not a separate phonemizer.
 *
 * ⚠️ LICENSE VARIES PER CHECKPOINT, and is NOT the same across the OuteTTS release line -
 * `OuteTTS-0.2-500M-GGUF` is CC-BY-NC-4.0 (non-commercial), `OuteTTS-0.3-500M-GGUF` is
 * CC-BY-SA-4.0 (share-alike), `OuteTTS-1.0-0.6B-GGUF` is Apache-2.0, and
 * `Llama-OuteTTS-1.0-1B-GGUF` is CC-BY-NC-SA-4.0 (non-commercial) - see the facts doc §0 for the
 * full table and sources. Because license is a genuine per-checkpoint fact (not a constant of
 * "OuteTTS" as a family), CLAUDE.md rule 1 forbids this engine from hardcoding one: it is
 * DISCOVERED from the bundle manifest's `"license"` field (never guessed, never defaulted) and
 * surfaced honestly in two places - [ModelDescriptor.displayName] (`"OuteTTS (<license>)"`, so
 * the picker shows it with zero UI change) and [ModelDescriptor.assetPaths] under
 * [LICENSE_ASSET_KEY] (a documented non-path exception, structured access for anything that wants
 * to read it programmatically - e.g. a future "this model is non-commercial" banner). See
 * `engines/outetts/INTEGRATION.md` for why this matters for a paid app.
 *
 * FINGERPRINT (fail-closed, spec §9.1): a bundle is claimed only when it contains, together:
 *  1. Exactly ONE `<name>.gguf` file with a companion `<name>.gguf.json` manifest sidecar naming
 *     a non-blank `"decoder"` id, a non-blank `"decoder_file"` that is itself present among the
 *     bundle's files, a positive `"sample_rate"`, and a non-blank `"license"` - all DISCOVERED
 *     facts (CLAUDE.md rule 1: none of these is this engine's to invent, and every one of them
 *     genuinely varies per checkpoint - see the facts doc). Zero or more-than-one such candidate
 *     is ambiguous (which LLM would a single `load()` actually use?) and is refused, not guessed.
 *  2. At least one `<voiceId>.speaker.json` side file - OuteTTS voices are speaker PROFILES (a
 *     small JSON of pre-tokenized reference audio, facts doc §4), not baked discrete voice ids
 *     like Piper/Kokoro, so the voice roster is discovered from whichever profile files the
 *     bundle ships, never a hardcoded list. A bundle with an otherwise-valid LLM+manifest but NO
 *     speaker profile cannot synthesize anything and is refused (mirrors
 *     [ModelDescriptor]'s own `voices.isNotEmpty()` invariant).
 * [forcedMatch] is more permissive about ambiguity (picks the first LLM candidate rather than
 * refuse, per its contract of never refusing an explicit user choice) but still throws when there
 * is no usable LLM+manifest pair at all, or no speaker profile at all - it cannot invent a sample
 * rate, decoder, license, or a voice out of nothing.
 *
 * PARAMETERS: intentionally empty. OuteTTS's autoregressive generation has no native speed/
 * duration knob (unlike Piper's `length_scale`); CLAUDE.md rule 2 forbids resampling output audio
 * to fake one (that shifts pitch). The descriptor therefore reports the locked `1.0..1.0` range,
 * exactly the same honest-closed posture [com.phonetts.engines.cosyvoice2.CosyVoice2Engine] and
 * [com.phonetts.engines.ggmltts.GgmlTtsEngine] already use for the same reason.
 *
 * DECODER ROUTING: like [com.phonetts.engines.ggmltts.GgmlTtsEngine]'s CrispASR `--backend` id,
 * the discovered decoder family (`"wavtokenizer"` / `"dac"`) and the decoder GGUF's file name are
 * not asset PATHS in the sense every other `assetPaths` entry is, so they ride the same documented
 * exception: [RuntimeOptions.extras] under [DECODER_TYPE_OPTION_KEY]/[DECODER_FILE_OPTION_KEY] for
 * the live `load()` call, and (so they survive a save/reload of the persisted descriptor) as non-
 * path `assetPaths` entries under [DECODER_TYPE_ASSET_KEY]/the plain [DECODER_ASSET_KEY] (which
 * IS a real path, unlike the type/license keys - see each constant's own doc).
 */
internal class OuteTtsEngine(
    context: EngineContext,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private var state: LoadedState? = null

    override fun isLoaded(): Boolean = state != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val llmCandidates = llmEntries(bundle)
        val llm = llmCandidates.singleOrNull() ?: return null
        val voices = voiceEntries(bundle)
        if (voices.isEmpty()) return null
        return EngineMatch(id, buildDescriptor(bundle, llm, voices, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val llm =
            llmEntries(bundle).firstOrNull()
                ?: throw IllegalArgumentException(
                    "bundle '${bundle.id}' has no <name>.gguf + <name>.gguf.json manifest pair this" +
                        " engine recognizes - an OuteTTS voice needs its LLM weights and a manifest" +
                        " naming its decoder, decoder file, sample rate, and license",
                )
        val voices = voiceEntries(bundle)
        require(voices.isNotEmpty()) {
            "bundle '${bundle.id}' has an OuteTTS LLM but no <voiceId>.speaker.json profile - there" +
                " is no voice to synthesize with"
        }
        return EngineMatch(id, buildDescriptor(bundle, llm, voices, Origin.SIDELOADED))
    }

    override fun isRuntimeAvailable(): Boolean = requireNativeTtsRuntime().isAvailable()

    override fun runtimeUnavailableMessage(): String =
        "$engineLabel needs its native GGUF runtime (build the app with -PwithOuteTts=true," +
            " see engines/outetts/INTEGRATION.md); it is not available on this device, so this" +
            " model cannot load"

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        val runtime = requireNativeTtsRuntime()
        val llmPath = requireAssetPath(descriptor, LLM_ASSET_KEY, engineLabel)
        val decoderPath = requireAssetPath(descriptor, DECODER_ASSET_KEY, engineLabel)
        val decoderType =
            descriptor.assetPaths[DECODER_TYPE_ASSET_KEY]
                ?: error("$engineLabel descriptor '${descriptor.modelId}' is missing its decoder type")
        val modelDir = llmPath.substringBeforeLast('/', missingDelimiterValue = ".")
        val decoderFileName = decoderPath.substringAfterLast('/')

        unload()
        val options =
            RuntimeOptions(
                extras =
                    mapOf(
                        DECODER_TYPE_OPTION_KEY to decoderType,
                        DECODER_FILE_OPTION_KEY to decoderFileName,
                    ),
            )
        val session = runtime.openTtsSession(modelDir, options)
        state = LoadedState(descriptor, session, voicesFrom(session, descriptor))
    }

    override fun unload() {
        state?.let { runCatching { it.session.close() } }
        state = null
    }

    // After load(), the voice list is the SSOT set the native session reports; before load() (the
    // picker showing a not-yet-loaded model) fall back to the descriptor's own discovered voices.
    override fun voices(): List<Voice> = state?.voices ?: state?.descriptor?.voices ?: emptyList()

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val loaded = checkNotNull(state) { "$engineLabel.synthesizeSentence called before load()" }
        // params is intentionally unused: OuteTTS's native generation has no routed speed knob (see
        // class KDoc "PARAMETERS"), so nothing is resampled either (CLAUDE.md rule 2).
        val voiceName = loaded.voices.firstOrNull { it.id == voiceId }?.id ?: loaded.voices.first().id
        return loaded.session.synthesize(NativeTtsRequest(text = sentence, voiceName = voiceName))
    }

    private fun requireNativeTtsRuntime(): NativeTtsRuntime {
        val runtime = requireRuntime(context, NATIVE_RUNTIME_ID, engineLabel)
        return runtime as? NativeTtsRuntime
            ?: error("$engineLabel: runtime '$NATIVE_RUNTIME_ID' is registered but is not a NativeTtsRuntime")
    }

    private fun voicesFrom(
        session: NativeTtsSession,
        descriptor: ModelDescriptor,
    ): List<Voice> {
        val names = session.voiceNames
        if (names.isEmpty()) return descriptor.voices
        val byId = descriptor.voices.associateBy { it.id }
        return names.map { name ->
            byId[name] ?: Voice(id = name, name = prettyVoiceName(name), language = DEFAULT_LANGUAGE)
        }
    }

    /** One `<name>.gguf` + `<name>.gguf.json` pair this engine recognized as the LLM backbone. */
    private data class LlmEntry(
        val ggufFile: String,
        val decoder: String,
        val decoderFile: String,
        val sampleRate: Int,
        val license: String,
    )

    /** One `<voiceId>.speaker.json` side file this engine recognized as a speaker profile voice. */
    private data class VoiceEntry(
        val voiceId: String,
        val speakerFile: String,
    )

    private fun llmEntries(bundle: ModelBundle): List<LlmEntry> =
        bundle.fileNames.filter { it.endsWith(GGUF_SUFFIX) }.mapNotNull { llmEntryFor(bundle, it) }

    /** The parsed `<name>.gguf.json` manifest object, or null if absent/unparseable (fail-closed). */
    private fun manifestObject(
        bundle: ModelBundle,
        ggufFile: String,
    ): Map<String, JsonValue>? {
        val manifest = bundle.sideFile("$ggufFile$MANIFEST_SUFFIX") ?: return null
        return MiniJson.parse(manifest)?.asObjectOrNull()
    }

    private fun llmEntryFor(
        bundle: ModelBundle,
        ggufFile: String,
    ): LlmEntry? {
        val obj = manifestObject(bundle, ggufFile) ?: return null
        val decoder = obj["decoder"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val decoderFile = obj["decoder_file"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val sampleRate = obj["sample_rate"]?.asIntOrNull()?.takeIf { it > 0 }
        val license = obj["license"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        // Direct null-checks (not a precomputed Boolean) so the compiler can smart-cast every
        // field to non-null below - still fail-closed. Split into two 2-way guards to stay under
        // detekt's ComplexCondition threshold while keeping ReturnCount headroom.
        if (decoder == null || decoderFile == null) return null
        if (sampleRate == null || license == null) return null
        if (decoderFile !in bundle.fileNames) return null
        return LlmEntry(
            ggufFile = ggufFile,
            decoder = decoder,
            decoderFile = decoderFile,
            sampleRate = sampleRate,
            license = license,
        )
    }

    private fun voiceEntries(bundle: ModelBundle): List<VoiceEntry> =
        bundle.fileNames
            .filter { it.endsWith(SPEAKER_SUFFIX) }
            .map { VoiceEntry(voiceId = it.removeSuffix(SPEAKER_SUFFIX), speakerFile = it) }

    private fun buildDescriptor(
        bundle: ModelBundle,
        llm: LlmEntry,
        voices: List<VoiceEntry>,
        origin: Origin,
    ): ModelDescriptor {
        val voiceList =
            voices.map { Voice(id = it.voiceId, name = prettyVoiceName(it.voiceId), language = DEFAULT_LANGUAGE) }
        val assetPaths = mutableMapOf<String, String>()
        assetPaths[LLM_ASSET_KEY] = joinAssetPath(bundle, llm.ggufFile)
        assetPaths[DECODER_ASSET_KEY] = joinAssetPath(bundle, llm.decoderFile)
        // Documented exceptions (see class KDoc "DECODER ROUTING" / "⚠️ LICENSE"): not paths.
        assetPaths[DECODER_TYPE_ASSET_KEY] = llm.decoder
        assetPaths[LICENSE_ASSET_KEY] = llm.license
        voices.forEach { assetPaths[it.voiceId] = joinAssetPath(bundle, it.speakerFile) }

        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            // License surfaced honestly right in the name the picker shows, no UI change needed -
            // see class KDoc "⚠️ LICENSE VARIES PER CHECKPOINT".
            displayName = "$DISPLAY_NAME (${llm.license})",
            origin = origin,
            sampleRate = llm.sampleRate,
            voices = voiceList,
            defaultVoiceId = voiceList.first().id,
            // No tunable parameters: see class KDoc "PARAMETERS" - honest-closed, no native speed
            // knob to route to (CLAUDE.md rule 2 forbids faking one via resampling).
            parameters = emptyList(),
            assetPaths = assetPaths,
            // No a-priori estimate: footprint varies by which GGUF quantization was downloaded and
            // nothing in the manifest declares it - UNKNOWN is the honest answer (rule 1).
            resourceCost = ResourceCost.UNKNOWN,
        )
    }

    /** Everything the loaded engine needs to synthesize; null while unloaded (spec §5.5). */
    private class LoadedState(
        val descriptor: ModelDescriptor,
        val session: NativeTtsSession,
        val voices: List<Voice>,
    )

    companion object {
        const val ENGINE_ID = "outetts"
        private const val DISPLAY_NAME = "OuteTTS"

        /** The native runtime this engine looks up - not yet built, see engines/outetts/INTEGRATION.md. */
        const val NATIVE_RUNTIME_ID = "outetts"

        /** [ModelDescriptor.assetPaths] key for the LLM backbone GGUF's on-device path (a real path). */
        const val LLM_ASSET_KEY = "outetts-llm"

        /** [ModelDescriptor.assetPaths] key for the decoder GGUF's on-device path (a real path). */
        const val DECODER_ASSET_KEY = "outetts-decoder"

        /** [ModelDescriptor.assetPaths] key for the decoder family id (NOT a path - see KDoc). */
        const val DECODER_TYPE_ASSET_KEY = "outetts-decoder-type"

        /** [ModelDescriptor.assetPaths] key for the discovered per-checkpoint license (NOT a path). */
        const val LICENSE_ASSET_KEY = "outetts-license"

        /** The [RuntimeOptions.extras] key carrying the decoder family id for this load. */
        const val DECODER_TYPE_OPTION_KEY = "decoder"

        /** The [RuntimeOptions.extras] key carrying the decoder GGUF's file name for this load. */
        const val DECODER_FILE_OPTION_KEY = "decoder_file"

        const val GGUF_SUFFIX = ".gguf"
        const val MANIFEST_SUFFIX = ".json"
        const val SPEAKER_SUFFIX = ".speaker.json"

        private const val DEFAULT_LANGUAGE = "en"

        private fun prettyVoiceName(raw: String): String =
            raw.split('-', '_').joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
}
