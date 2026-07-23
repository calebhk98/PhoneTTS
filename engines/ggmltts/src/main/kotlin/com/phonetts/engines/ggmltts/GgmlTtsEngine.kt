package com.phonetts.engines.ggmltts

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
import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull
import com.phonetts.engines.common.requireRuntime

/**
 * The generalized GGUF/ggml TTS engine - a single, backend-agnostic delegate over CrispStrobe/
 * CrispASR's ggml runtime (docs/COSYVOICE2.md), generalized past the CosyVoice3-only bridge
 * (`NativeCosyVoiceRuntime`, id `"cosyvoice"`). CrispASR is a 34-backend project; its own README
 * shows the SAME native library serving `--backend piper`, `--backend kokoro`, `--backend melotts`,
 * etc. (see `docs/research/runtime-feasibility-2026-07.md` §2, and `cstr/piper-voices-GGUF`, whose
 * README confirms it was produced by CrispASR's `convert-piper-to-gguf.py` and is run with
 * `crispasr --backend piper -m <voice>.gguf --tts "..."`). This engine is the Kotlin side of that
 * generalization: it never hardcodes a specific model family, only the shape "a `.gguf` weights
 * file plus a small companion manifest telling us which CrispASR backend it needs."
 *
 * Like [com.phonetts.engines.cosyvoice2.CosyVoice2Engine] (its closest template), this is a thin
 * delegate over the non-ONNX [NativeTtsRuntime] seam (id [NATIVE_RUNTIME_ID], `"ggml"`) - the
 * native side does the ENTIRE text→audio pipeline in one call, so there is no Kotlin
 * `TextFrontend` here either.
 *
 * FINGERPRINT (fail-closed, spec §9.1): a bundle is claimed only when at least one `<name>.gguf`
 * file has a companion `<name>.gguf.json` sidecar (mirroring Piper's own `<voice>.onnx` +
 * `<voice>.onnx.json` convention) naming a non-blank `"backend"` (the CrispASR `--backend` id) and
 * a positive `"sample_rate"` - both DISCOVERED facts, never fabricated (CLAUDE.md rule 1: sample
 * rate is not this engine's to invent, and it genuinely varies per backend/voice - e.g. Piper
 * voices ship at 16 kHz or 22.05 kHz depending on which one). A bundle whose multiple voice
 * sidecars disagree on `backend`/`sample_rate` is refused by [inspect] (ambiguous which single
 * runtime call would serve every voice) rather than guessed; [forcedMatch] is more permissive,
 * per its contract, and picks the first entry's backend/sample rate instead of refusing.
 *
 * PARAMETERS: intentionally empty. [com.phonetts.core.runtime.NativeTtsRequest] - the one payload
 * every [NativeTtsRuntime] backend accepts - carries no speed field (same honest-closed reasoning
 * CosyVoice3 uses): routing speed to a *specific* CrispASR backend's native knob (Piper's
 * `length_scale`, say) would need a per-backend argument this shared seam does not yet carry.
 * Advertising a fake speed control here would violate rule 2 (never resample to fake speed); the
 * descriptor therefore reports the locked `1.0..1.0` range like CosyVoice3, and a future ticket
 * that threads a native speed argument through [NativeTtsRequest] can turn this on for real
 * without any other change (see `engines/ggmltts/INTEGRATION.md`).
 *
 * BACKEND ROUTING: [ModelDescriptor.assetPaths] has no reserved slot for anything but a file path,
 * and `:core` is intentionally left unmodified by this ticket, so the discovered CrispASR backend
 * id is carried the one place a per-model fact CAN ride without a `:core` change: as a `String` in
 * [RuntimeOptions.extras] under [BACKEND_OPTION_KEY], AND (so the id also survives a
 * save/reload of the persisted descriptor) as a non-path entry in `assetPaths` under
 * [BACKEND_ASSET_KEY] - documented clearly here as the one intentional exception to "assetPaths
 * values are always on-device paths."
 */
internal class GgmlTtsEngine(
    context: EngineContext,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private var state: LoadedState? = null

    override fun isLoaded(): Boolean = state != null

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val entries = voiceEntries(bundle)
        if (entries.isEmpty()) return null
        val backend = uniformOrNull(entries.map { it.backend }) ?: return null
        val sampleRate = uniformOrNull(entries.map { it.sampleRate }) ?: return null
        return EngineMatch(id, buildDescriptor(bundle, entries, backend, sampleRate, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val entries = voiceEntries(bundle)
        require(entries.isNotEmpty()) {
            "bundle '${bundle.id}' has no <name>.gguf + <name>.gguf.json manifest pair this engine" +
                " recognizes - a ggml TTS voice needs both its weights and a manifest naming its" +
                " CrispASR backend + sample rate"
        }
        val backend = uniformOrNull(entries.map { it.backend }) ?: entries.first().backend
        val sampleRate = uniformOrNull(entries.map { it.sampleRate }) ?: entries.first().sampleRate
        return EngineMatch(id, buildDescriptor(bundle, entries, backend, sampleRate, Origin.SIDELOADED))
    }

    override fun isRuntimeAvailable(): Boolean = requireNativeTtsRuntime().isAvailable()

    override fun runtimeUnavailableMessage(): String =
        "$engineLabel needs the generalized native ggml backend (build the app with" +
            " -PwithGgmlTts=true, see engines/ggmltts/INTEGRATION.md); it is not available on this" +
            " device, so this model cannot load"

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        val runtime = requireNativeTtsRuntime()
        val backend =
            descriptor.assetPaths[BACKEND_ASSET_KEY]
                ?: error("$engineLabel descriptor '${descriptor.modelId}' is missing its backend id")
        val voicePath =
            descriptor.assetPaths.entries.firstOrNull { it.key != BACKEND_ASSET_KEY }?.value
                ?: error("$engineLabel descriptor '${descriptor.modelId}' has no voice weight asset path")
        val modelDir = voicePath.substringBeforeLast('/', missingDelimiterValue = ".")

        unload()
        val options = RuntimeOptions(extras = mapOf(BACKEND_OPTION_KEY to backend))
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
        // params is intentionally unused: no ggml backend has a routed native speed knob through
        // this shared NativeTtsRequest payload yet (see class KDoc), so nothing is resampled either
        // (CLAUDE.md rule 2).
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

    /** One `<name>.gguf` + `<name>.gguf.json` pair this engine recognized as a voice. */
    private data class VoiceEntry(
        val voiceId: String,
        val ggufFile: String,
        val backend: String,
        val sampleRate: Int,
        val language: String,
    )

    private fun voiceEntries(bundle: ModelBundle): List<VoiceEntry> =
        bundle.fileNames.filter { it.endsWith(GGUF_SUFFIX) }.mapNotNull { voiceEntryFor(bundle, it) }

    private fun voiceEntryFor(
        bundle: ModelBundle,
        ggufFile: String,
    ): VoiceEntry? {
        val manifest = bundle.sideFile("$ggufFile$MANIFEST_SUFFIX") ?: return null
        val obj = MiniJson.parse(manifest)?.asObjectOrNull() ?: return null
        val backend = obj["backend"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sampleRate = obj["sample_rate"]?.asIntOrNull()?.takeIf { it > 0 } ?: return null
        val language = obj["language"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LANGUAGE
        return VoiceEntry(
            voiceId = ggufFile.removeSuffix(GGUF_SUFFIX),
            ggufFile = ggufFile,
            backend = backend,
            sampleRate = sampleRate,
            language = language,
        )
    }

    private fun <T> uniformOrNull(values: List<T>): T? = values.distinct().singleOrNull()

    private fun buildDescriptor(
        bundle: ModelBundle,
        entries: List<VoiceEntry>,
        backend: String,
        sampleRate: Int,
        origin: Origin,
    ): ModelDescriptor {
        val voices = entries.map { Voice(id = it.voiceId, name = prettyVoiceName(it.voiceId), language = it.language) }
        val assetPaths = mutableMapOf<String, String>()
        entries.forEach { assetPaths[it.voiceId] = joinAssetPath(bundle, it.ggufFile) }
        // Documented exception (see class KDoc "BACKEND ROUTING"): not a filesystem path.
        assetPaths[BACKEND_ASSET_KEY] = backend

        return ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            displayName = "$DISPLAY_NAME (${prettyVoiceName(backend)})",
            origin = origin,
            sampleRate = sampleRate,
            voices = voices,
            defaultVoiceId = voices.first().id,
            // No tunable parameters: see class KDoc "PARAMETERS" - honest-closed until a native
            // speed argument is threaded through NativeTtsRequest for a specific backend.
            parameters = emptyList(),
            assetPaths = assetPaths,
            // No a-priori estimate: unlike CosyVoice3 (one fixed model family), this engine's
            // footprint varies arbitrarily by whichever CrispASR backend/voice was downloaded, and
            // nothing in the manifest declares it - UNKNOWN is the honest answer (rule 1: never
            // fabricate a resource fact either).
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
        const val ENGINE_ID = "ggmltts"
        private const val DISPLAY_NAME = "GGML TTS"

        /** The generalized, backend-parameterized native runtime this engine looks up. */
        const val NATIVE_RUNTIME_ID = "ggml"

        /** The [RuntimeOptions.extras] key carrying the CrispASR `--backend` id for this load. */
        const val BACKEND_OPTION_KEY = "backend"

        /** The [ModelDescriptor.assetPaths] key carrying the backend id (not a path - see KDoc). */
        const val BACKEND_ASSET_KEY = "ggml-backend"

        const val GGUF_SUFFIX = ".gguf"
        const val MANIFEST_SUFFIX = ".json"

        private const val DEFAULT_LANGUAGE = "en"

        private fun prettyVoiceName(raw: String): String =
            raw.split('-', '_').joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
}
