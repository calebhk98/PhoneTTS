package com.phonetts.engines.neutts

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
import com.phonetts.engines.common.json.asArrayOrNull
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull
import com.phonetts.engines.common.requireAssetPath
import com.phonetts.engines.common.requireRuntime

/**
 * NeuTTS Nano (Neuphonic, `neuphonic/neutts-nano` + GGUF siblings, see
 * `docs/research/neutts-facts.md` for every cited fact below) - a small autoregressive codec-LM:
 * a compact Llama-family LLM backbone (NOT Qwen - that naming belongs to the larger sibling,
 * NeuTTS **Air** - `docs/research/neutts-facts.md` §2) generates NeuCodec speech-token ids one at
 * a time, which NeuCodec's decoder turns into 24 kHz PCM. Shipped as GGUF, run via
 * `llama-cpp-python`/llama.cpp upstream - a ggml/llama.cpp family model, like
 * [com.phonetts.engines.ggmltts.GgmlTtsEngine] and CosyVoice3, so this engine is likewise a thin
 * delegate over the non-ONNX [NativeTtsRuntime] seam (id [NATIVE_RUNTIME_ID]) rather than the ONNX
 * `Runtime`.
 *
 * UNLIKE `GgmlTtsEngine`/CosyVoice3, though, this is genuinely a **two-stage** pipeline (LLM
 * backbone → NeuCodec decode, `docs/research/neutts-facts.md` §2) that is **not** one of
 * CrispASR's existing 34 backends (verified directly against CrispASR's own `docs/tts.md` - see
 * §6 of the facts doc), so it needs its **own** [NativeTtsRuntime] implementation
 * (`NativeNeuTtsRuntime`, app-module, PENDING - see `engines/neutts/INTEGRATION.md`), registered
 * under a distinct id ([NATIVE_RUNTIME_ID], `"neutts"`) rather than reusing `GgmlTtsEngine`'s
 * `"ggml"` id.
 *
 * FRONTEND: unlike `GgmlTtsEngine`/CosyVoice3 (whose native pipeline tokenizes text internally
 * with no Kotlin frontend at all), NeuTTS genuinely needs one - whether a given GGUF backbone
 * wants espeak-ng phonemes or raw/BPE text is a **discovered, per-checkpoint fact** read from the
 * GGUF's own embedded metadata (`docs/research/neutts-facts.md` §4), so it rides in the bundle
 * manifest (see FINGERPRINT below) as `"input_format"` rather than being assumed. When it says
 * [PHONEME_INPUT_FORMAT], this engine phonemizes each sentence via [EngineContext.phonemizer] (the
 * shared espeak-ng-backed seam - `docs/espeak-ng-integration.md`) before handing text to the
 * native pipeline; any other value passes the sentence through unchanged (fail-open on the
 * simpler path for an unrecognized value, never silently wrong - facts doc §8).
 *
 * VOICES / CLONING: NeuTTS Nano is a **voice-cloning** model, not a fixed-speaker one (that's the
 * unrelated NeuTTS-2E checkpoint - facts doc §3) - a "voice" here is a (reference-audio,
 * reference-transcript) pair the native pipeline encodes once at load time and reuses as a
 * speaker prompt for every synthesis call. This engine therefore never invents a "voices" list;
 * it reads exactly the (ref-audio, ref-text) entries the bundle manifest declares, reflecting the
 * cloning nature honestly rather than papering over it with fabricated fixed-speaker names.
 *
 * FINGERPRINT (fail-closed, spec §9.1): a `<name>.gguf` file is claimed as the backbone only when
 * it has a `<name>.gguf.json` sidecar declaring a positive `"sample_rate"`, a non-blank
 * `"input_format"`, a `"codec_decoder"` file name that is ALSO present in the bundle (no decoder,
 * no audio path), and a non-empty `"voices"` array where every entry has a non-blank `"id"`, a
 * `"ref_audio"` file name present in the bundle, and a non-blank `"ref_text"` (a malformed
 * individual voice entry is dropped, not fatal, but the candidate needs at least one valid voice
 * left - the same two-tier leniency `GgmlTtsEngine` applies to its own manifest fields). Exactly
 * one such `<gguf, manifest>` candidate must exist for [inspect] to auto-claim a bundle - more
 * than one is ambiguous (which file is really the backbone?) and refused rather than guessed, none
 * is refused outright. [forcedMatch] is more permissive per its contract and picks the first valid
 * candidate instead of refusing on ambiguity, but still throws if the bundle has no usable
 * candidate at all (cannot invent a sample rate or a voice list any more than `GgmlTtsEngine` can).
 * See `engines/neutts/INTEGRATION.md` §7 for the exact manifest JSON shape.
 *
 * PARAMETERS: intentionally empty. No NeuTTS example or API surfaces a speed/duration argument
 * (`docs/research/neutts-facts.md` §5) - CLAUDE.md rule 2 forbids resampling output to fake one, so
 * the descriptor reports the locked `1.0..1.0` range, honest-closed, exactly like `GgmlTtsEngine`
 * and CosyVoice3.
 *
 * ASSET-PATH EXCEPTIONS: like `GgmlTtsEngine.BACKEND_ASSET_KEY`, [INPUT_FORMAT_ASSET_KEY] and the
 * per-voice `ref-text:<voiceId>` entries carry a discovered STRING fact (not an on-device path) so
 * it survives a save/reload of the persisted descriptor - `load()` never re-reads the original
 * bundle, only the descriptor.
 */
internal class NeuTtsEngine(
    context: EngineContext,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private var state: LoadedState? = null

    override fun isLoaded(): Boolean = state != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val manifest = candidateManifests(bundle).singleOrNull() ?: return null
        return EngineMatch(id, buildDescriptor(bundle, manifest, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val manifest =
            candidateManifests(bundle).firstOrNull()
                ?: throw IllegalArgumentException(
                    "bundle '${bundle.id}' has no <name>.gguf + <name>.gguf.json manifest this engine" +
                        " recognizes - a NeuTTS bundle needs its backbone weights, a codec decoder file" +
                        " named in the manifest, and at least one (ref_audio, ref_text) voice entry",
                )
        return EngineMatch(id, buildDescriptor(bundle, manifest, Origin.SIDELOADED))
    }

    override fun isRuntimeAvailable(): Boolean = requireNativeTtsRuntime().isAvailable()

    override fun runtimeUnavailableMessage(): String =
        "$engineLabel needs its own native llama.cpp + NeuCodec runtime (build the app with" +
            " -PwithNeuTts=true, see engines/neutts/INTEGRATION.md); it is not available on this" +
            " device, so this model cannot load"

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        val runtime = requireNativeTtsRuntime()
        val ggufPath = requireAssetPath(descriptor, GGUF_ASSET_KEY, engineLabel)
        val codecDecoderPath = requireAssetPath(descriptor, CODEC_DECODER_ASSET_KEY, engineLabel)
        val inputFormat = descriptor.assetPaths[INPUT_FORMAT_ASSET_KEY] ?: DEFAULT_INPUT_FORMAT
        val modelDir = ggufPath.substringBeforeLast('/', missingDelimiterValue = ".")

        unload()
        val options = RuntimeOptions(extras = mapOf(CODEC_DECODER_OPTION_KEY to codecDecoderPath))
        val session = runtime.openTtsSession(modelDir, options)
        state = LoadedState(descriptor, session, voicesFrom(session, descriptor), inputFormat)
    }

    override fun unload() {
        state?.let { runCatching { it.session.close() } }
        state = null
    }

    // After load(), the voice list is the SSOT set the native session reports (it re-encodes the
    // reference clips itself); before load() (the picker showing a not-yet-loaded model) fall back
    // to the descriptor's own discovered voices - same fallback GgmlTtsEngine uses.
    override fun voices(): List<Voice> = state?.voices ?: state?.descriptor?.voices ?: emptyList()

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray {
        val loaded = checkNotNull(state) { "$engineLabel.synthesizeSentence called before load()" }
        // params is intentionally unused: no NeuTTS example exposes a native speed/duration knob
        // (see class KDoc "PARAMETERS"), so nothing is resampled either (CLAUDE.md rule 2).
        val voice = loaded.voices.firstOrNull { it.id == voiceId } ?: loaded.voices.first()
        val text =
            if (loaded.inputFormat == PHONEME_INPUT_FORMAT) {
                context.phonemizer.phonemize(sentence, voice.language)
            } else {
                sentence
            }
        return loaded.session.synthesize(NativeTtsRequest(text = text, voiceName = voice.id))
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

    /** One `<name>.gguf` + `<name>.gguf.json` pair this engine recognizes as a full backbone+voice bundle. */
    private data class NeuTtsManifest(
        val ggufFile: String,
        val sampleRate: Int,
        val inputFormat: String,
        val codecDecoderFile: String,
        val voices: List<VoiceEntry>,
    )

    /** One `(ref_audio, ref_text)` clone-voice entry declared in the manifest's `"voices"` array. */
    private data class VoiceEntry(
        val voiceId: String,
        val displayName: String,
        val language: String,
        val refAudioFile: String,
        val refText: String,
    )

    private fun candidateManifests(bundle: ModelBundle): List<NeuTtsManifest> =
        bundle.fileNames.filter { it.endsWith(GGUF_SUFFIX) }.mapNotNull { manifestFor(bundle, it) }

    private fun manifestFor(
        bundle: ModelBundle,
        ggufFile: String,
    ): NeuTtsManifest? {
        val manifestText = bundle.sideFile("$ggufFile$MANIFEST_SUFFIX") ?: return null
        val obj = MiniJson.parse(manifestText)?.asObjectOrNull() ?: return null
        val scalars = scalarsFrom(bundle, obj) ?: return null
        val voices = voiceEntriesFrom(bundle, obj["voices"])
        if (voices.isEmpty()) return null
        return NeuTtsManifest(ggufFile, scalars.sampleRate, scalars.inputFormat, scalars.codecDecoderFile, voices)
    }

    /** The three scalar manifest fields (everything but `"voices"`), split out to keep [manifestFor] flat. */
    private data class ManifestScalars(
        val sampleRate: Int,
        val inputFormat: String,
        val codecDecoderFile: String,
    )

    private fun scalarsFrom(
        bundle: ModelBundle,
        obj: Map<String, JsonValue>,
    ): ManifestScalars? {
        val sampleRate = obj["sample_rate"]?.asIntOrNull()?.takeIf { it > 0 } ?: return null
        val inputFormat = obj["input_format"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val codecDecoderFile =
            obj["codec_decoder"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() && bundle.hasFile(it) }
                ?: return null
        return ManifestScalars(sampleRate, inputFormat, codecDecoderFile)
    }

    private fun voiceEntriesFrom(
        bundle: ModelBundle,
        voicesValue: JsonValue?,
    ): List<VoiceEntry> = voicesValue?.asArrayOrNull()?.mapNotNull { voiceEntryFrom(bundle, it) } ?: emptyList()

    private fun voiceEntryFrom(
        bundle: ModelBundle,
        value: JsonValue,
    ): VoiceEntry? {
        val obj = value.asObjectOrNull() ?: return null
        val voiceId = obj["id"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val refAudioFile =
            obj["ref_audio"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() && bundle.hasFile(it) } ?: return null
        val refText = obj["ref_text"]?.asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val name = obj["name"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: prettyVoiceName(voiceId)
        val language = obj["language"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LANGUAGE
        return VoiceEntry(voiceId, name, language, refAudioFile, refText)
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        manifest: NeuTtsManifest,
        origin: Origin,
    ): ModelDescriptor {
        val voices = manifest.voices.map { Voice(id = it.voiceId, name = it.displayName, language = it.language) }
        val assetPaths = mutableMapOf<String, String>()
        assetPaths[GGUF_ASSET_KEY] = joinAssetPath(bundle, manifest.ggufFile)
        assetPaths[CODEC_DECODER_ASSET_KEY] = joinAssetPath(bundle, manifest.codecDecoderFile)
        // Documented exception (see class KDoc "ASSET-PATH EXCEPTIONS"): not a filesystem path.
        assetPaths[INPUT_FORMAT_ASSET_KEY] = manifest.inputFormat
        manifest.voices.forEach { voice ->
            assetPaths[refAudioAssetKey(voice.voiceId)] = joinAssetPath(bundle, voice.refAudioFile)
            // Also a documented non-path exception - the reference transcript text itself.
            assetPaths[refTextAssetKey(voice.voiceId)] = voice.refText
        }

        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = DISPLAY_NAME,
            origin = origin,
            sampleRate = manifest.sampleRate,
            voices = voices,
            defaultVoiceId = voices.first().id,
            // No tunable parameters: see class KDoc "PARAMETERS".
            parameters = emptyList(),
            assetPaths = assetPaths,
            // No a-priori estimate: not benchmarked (docs/research/neutts-facts.md §8) - UNKNOWN is
            // the honest answer (CLAUDE.md rule 1: never fabricate a resource fact either).
            resourceCost = ResourceCost.UNKNOWN,
        )
    }

    /** Everything the loaded engine needs to synthesize; null while unloaded (spec §5.5). */
    private class LoadedState(
        val descriptor: ModelDescriptor,
        val session: NativeTtsSession,
        val voices: List<Voice>,
        val inputFormat: String,
    )

    companion object {
        const val ENGINE_ID = "neutts"
        private const val DISPLAY_NAME = "NeuTTS Nano"

        /** This engine's OWN native runtime id - deliberately distinct from GgmlTtsEngine's "ggml". */
        const val NATIVE_RUNTIME_ID = "neutts"

        /** [ModelDescriptor.assetPaths] key for the backbone `.gguf` weights path. */
        const val GGUF_ASSET_KEY = "neutts-gguf"

        /** [ModelDescriptor.assetPaths] key for the NeuCodec decoder asset path. */
        const val CODEC_DECODER_ASSET_KEY = "neutts-codec-decoder"

        /** The [ModelDescriptor.assetPaths] key carrying the discovered input format (not a path). */
        const val INPUT_FORMAT_ASSET_KEY = "neutts-input-format"

        /** The [RuntimeOptions.extras] key carrying the codec decoder's on-device path for this load. */
        const val CODEC_DECODER_OPTION_KEY = "codec_decoder_path"

        /** The manifest `"input_format"` value that routes synthesis through [EngineContext.phonemizer]. */
        const val PHONEME_INPUT_FORMAT = "phonemes"

        const val GGUF_SUFFIX = ".gguf"
        const val MANIFEST_SUFFIX = ".json"

        // Used only when a descriptor predates this field (defensive; every descriptor this engine
        // itself builds always sets INPUT_FORMAT_ASSET_KEY) - the simpler, never-silently-wrong path
        // per the class KDoc "FRONTEND" note.
        private const val DEFAULT_INPUT_FORMAT = "bpe"

        private const val DEFAULT_LANGUAGE = "en"

        private fun refAudioAssetKey(voiceId: String): String = "ref-audio:$voiceId"

        private fun refTextAssetKey(voiceId: String): String = "ref-text:$voiceId"

        private fun prettyVoiceName(raw: String): String =
            raw.split('-', '_').joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
}
