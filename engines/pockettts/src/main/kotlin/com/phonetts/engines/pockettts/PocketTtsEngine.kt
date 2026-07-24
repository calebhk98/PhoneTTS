package com.phonetts.engines.pockettts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.model.ResourceCost
import com.phonetts.engines.common.AbstractVoiceEngine
import com.phonetts.engines.common.joinAssetPath
import com.phonetts.engines.common.json.JsonValue
import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asArrayOrNull
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull
import com.phonetts.engines.common.requireAssetPath

/**
 * Kyutai Pocket TTS (`kyutai/pocket-tts`, 100M params, AR codec-LM in the Moshi/Mimi lineage).
 * Code is MIT; the model **weights** are CC BY 4.0 (a stricter attribution term than the task's
 * "MIT" framing suggested - see `docs/research/pockettts-facts.md` "License nuance"). See that
 * doc for the full research trail and every source URL this KDoc cites.
 *
 * STATUS (honest-closed, deliberately the ggmltts/CosyVoice3 posture, NOT the KittenTTS one):
 * there is **no official Kyutai ONNX/ggml export**. A real, working, publicly downloadable
 * **community** export exists - [KevinAHM/pocket-tts-onnx-export](
 * https://github.com/KevinAHM/pocket-tts-onnx-export) (exporter) publishing weights + a Python
 * runtime at [KevinAHM/pocket-tts-onnx](https://huggingface.co/KevinAHM/pocket-tts-onnx)
 * (models CC-BY-4.0, code Apache-2.0) - and this engine's [inspect]/[forcedMatch]/descriptor
 * building against that bundle's real, sourced file layout ARE real. What is **not** real is
 * [synthesizeSentence]: the community runtime is not one ONNX graph but FIVE
 * (`text_conditioner` -> `flow_lm_main` -> an autoregressive Euler-integration flow-matching loop
 * over `flow_lm_flow` -> `mimi_decoder`), each carrying a PER-BUNDLE, dynamically-sized set of
 * named KV-cache/state tensors (`state_0..N` / `out_state_0..N`, discovered from the bundle's own
 * `flow_lm_state_manifest`/`mimi_state_manifest` - 18 entries for a 6-layer bundle, more for a
 * 24-layer one) that must be threaded through session calls by hand, PLUS a SentencePiece
 * tokenizer this codebase has no reader for, PLUS text chunking/EOS-detection/temperature-noise
 * logic that has to reproduce the reference `pocket_tts_onnx.py` driver
 * (`_run_flow_lm_chunk`/`generate`/`stream`) exactly. That reference driver ALSO defaults to a
 * live `hf_hub_download` call for built-in voice states unless a local `.safetensors` is
 * supplied - a network dependency this fully-offline app cannot inherit as-is (CLAUDE.md
 * "fully-offline"). Porting all of that correctly, with zero ability in this session to run it
 * against real weights and verify the audio, is exactly the fabrication risk CLAUDE.md rule 1
 * forbids ("if you cannot source the real I/O contract, DO NOT invent one") - the *shape* of the
 * I/O is sourced (cited above and in the facts doc), but the multi-step numerical algorithm that
 * drives it is not something this session can port and verify honestly. So [synthesizeSentence]
 * fails closed with a clear message instead of guessing at that port. See
 * `engines/pockettts/INTEGRATION.md` for exactly what a future session needs to finish it.
 *
 * FINGERPRINT (fail-closed, spec §9.1): claims a bundle only when it has the exact file layout
 * the community exporter produces - `bundle.json` (parsed for `flow_lm_state_manifest` AND
 * `mimi_state_manifest` arrays, which is what makes this schema unmistakably the
 * KevinAHM/pocket-tts-onnx-export layout and not some other model's generic config file), a
 * `sample_rate` and `bundle_name` string, `tokenizer.model`, and all five `<stem>.onnx` graphs
 * (`text_conditioner`/`mimi_encoder` are FP32-only per the export README; `flow_lm_main`/
 * `flow_lm_flow`/`mimi_decoder` may instead be present only as their `_int8.onnx` quantized
 * sibling). A bundle with no `predefined_voices` is refused by [inspect] (no default voice can be
 * chosen with confidence); [forcedMatch] is more permissive, per its contract, and falls back to
 * a single generic voice.
 */
internal class PocketTtsEngine(
    context: EngineContext,
) : AbstractVoiceEngine(context) {
    override val id: String = ENGINE_ID
    override val displayName: String = DISPLAY_NAME
    override val engineLabel: String = ENGINE_ID

    private var loaded = false
    private var loadedVoices: List<Voice> = emptyList()

    override fun isLoaded(): Boolean = loaded

    override fun inspect(bundle: ModelBundle): EngineMatch? {
        val facts = bundleFacts(bundle) ?: return null
        if (facts.voices.isEmpty()) return null
        return EngineMatch(id, buildDescriptor(bundle, facts, Origin.BUILT_IN))
    }

    override fun forcedMatch(bundle: ModelBundle): EngineMatch {
        val facts =
            requireNotNull(bundleFacts(bundle)) {
                "bundle '${bundle.id}' is not a Pocket TTS ONNX bundle: needs $BUNDLE_METADATA_FILE + " +
                    "$TOKENIZER_FILE + the 5 ONNX graphs this engine recognizes " +
                    "(see engines/pockettts/INTEGRATION.md)"
            }
        val withDefaultVoice = if (facts.voices.isEmpty()) facts.copy(voices = fallbackVoices(facts)) else facts
        return EngineMatch(id, buildDescriptor(bundle, withDefaultVoice, Origin.SIDELOADED))
    }

    // No runtime dependency: doLoad() below never opens an ONNX session (see class KDoc
    // "STATUS") - it only validates the descriptor's own asset paths, so this is always "true"
    // rather than gating on a runtime this engine does not yet drive.
    override fun isRuntimeAvailable(): Boolean = true

    override suspend fun doLoad(descriptor: ModelDescriptor) {
        // Nothing is actually loaded into an inference engine yet (see class KDoc); this only
        // confirms every asset path the descriptor promised is genuinely present, so a corrupt or
        // partially-downloaded bundle fails here with a clear message rather than only at
        // synthesis time.
        (ALL_GRAPH_STEMS + CONFIG_ASSET_KEY + TOKENIZER_ASSET_KEY).forEach { key ->
            requireAssetPath(descriptor, key, engineLabel)
        }
        loadedVoices = descriptor.voices
        loaded = true
    }

    override fun unload() {
        loaded = false
        loadedVoices = emptyList()
    }

    override fun voices(): List<Voice> = loadedVoices

    override fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray = error(inferencePendingMessage())

    private fun inferencePendingMessage(): String =
        "$engineLabel: inference is not implemented yet. Pocket TTS's real pipeline is 5 chained " +
            "ONNX graphs (text_conditioner -> flow_lm_main -> an Euler-integration flow-matching " +
            "loop over flow_lm_flow -> mimi_decoder) with per-bundle dynamic KV-cache state " +
            "threading (see this bundle's flow_lm_state_manifest/mimi_state_manifest) and a " +
            "SentencePiece tokenizer this codebase does not have yet - see " +
            "engines/pockettts/INTEGRATION.md for exactly what's verified vs. what remains."

    private fun fallbackVoices(facts: BundleFacts): List<Voice> =
        listOf(Voice(id = DEFAULT_VOICE_ID, name = "Default", language = facts.bundleName))

    // Split into small helpers (each with its own low return count, detekt ReturnCount) rather
    // than one long guard-clause chain: hasRequiredFiles()/resolveGraphAssets() check the file
    // layout, verifiedConfig() parses+fingerprints bundle.json, coreFacts() pulls the remaining
    // per-bundle values out of it. Behavior (fail-closed on any missing/malformed piece) is
    // unchanged from a single flat function.
    private fun bundleFacts(bundle: ModelBundle): BundleFacts? {
        if (!hasRequiredFiles(bundle)) return null
        val graphAssets = resolveGraphAssets(bundle) ?: return null
        val config = verifiedConfig(bundle) ?: return null
        val core = coreFacts(config) ?: return null

        val assetPaths =
            graphAssets +
                mapOf(
                    CONFIG_ASSET_KEY to joinAssetPath(bundle, BUNDLE_METADATA_FILE),
                    TOKENIZER_ASSET_KEY to joinAssetPath(bundle, TOKENIZER_FILE),
                )
        val voices = core.voiceNames.map { name -> Voice(id = name, name = name, language = core.bundleName) }
        return BundleFacts(core.bundleName, core.sampleRate, voices, assetPaths)
    }

    private fun hasRequiredFiles(bundle: ModelBundle): Boolean =
        bundle.hasFile(BUNDLE_METADATA_FILE) && bundle.hasFile(TOKENIZER_FILE)

    /**
     * Parses `bundle.json` and confirms it carries the two manifest arrays that fingerprint the
     * KevinAHM/pocket-tts-onnx-export schema (explicit per-layer stateful I/O), not some other
     * model's generic config file - see class KDoc. `null` if the side file is absent, malformed,
     * or missing either fingerprint array.
     */
    private fun verifiedConfig(bundle: ModelBundle): Map<String, JsonValue>? {
        val configText = bundle.sideFile(BUNDLE_METADATA_FILE) ?: return null
        val config = MiniJson.parse(configText)?.asObjectOrNull() ?: return null
        val hasManifests =
            config["flow_lm_state_manifest"]?.asArrayOrNull() != null &&
                config["mimi_state_manifest"]?.asArrayOrNull() != null
        return config.takeIf { hasManifests }
    }

    /** The remaining per-bundle values [bundleFacts] needs, pulled out of a [verifiedConfig] map. */
    private fun coreFacts(config: Map<String, JsonValue>): CoreFacts? {
        val bundleName = config["bundle_name"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sampleRate = config["sample_rate"]?.asIntOrNull()?.takeIf { it > 0 } ?: return null
        val voiceNames =
            config["predefined_voices"]?.asArrayOrNull()?.mapNotNull { it.asStringOrNull() } ?: emptyList()
        return CoreFacts(bundleName, sampleRate, voiceNames)
    }

    private data class CoreFacts(
        val bundleName: String,
        val sampleRate: Int,
        val voiceNames: List<String>,
    )

    /**
     * Resolves each of the 5 real graph stems to whichever file the bundle actually ships:
     * `text_conditioner`/`mimi_encoder` must be the plain FP32 `.onnx` (the export never
     * quantizes them); the other three accept either the FP32 file or its `_int8.onnx` sibling.
     * `null` if any stem is entirely missing - fail closed rather than claim a partial bundle.
     */
    private fun resolveGraphAssets(bundle: ModelBundle): Map<String, String>? {
        val assets = mutableMapOf<String, String>()
        for (stem in FP32_ONLY_STEMS) {
            val file = "$stem$ONNX_SUFFIX"
            if (!bundle.hasFile(file)) return null
            assets[stem] = joinAssetPath(bundle, file)
        }
        for (stem in QUANTIZABLE_STEMS) {
            val file = quantizableFile(bundle, stem) ?: return null
            assets[stem] = joinAssetPath(bundle, file)
        }
        return assets
    }

    private fun quantizableFile(
        bundle: ModelBundle,
        stem: String,
    ): String? {
        val fp32 = "$stem$ONNX_SUFFIX"
        if (bundle.hasFile(fp32)) return fp32
        val int8 = "$stem$INT8_SUFFIX"
        if (bundle.hasFile(int8)) return int8
        return null
    }

    private fun buildDescriptor(
        bundle: ModelBundle,
        facts: BundleFacts,
        origin: Origin,
    ): ModelDescriptor =
        ModelDescriptor(
            modelId = bundle.id,
            engineId = id,
            // The bundle's own "bundle_name" (e.g. "english_2026-04", "french_24l") disambiguates
            // the per-language bundles this family ships as (docs/research/pockettts-facts.md).
            displayName = "$DISPLAY_NAME (${facts.bundleName})",
            origin = origin,
            // VALIDATED per-bundle: KevinAHM/pocket-tts-onnx/onnx/english_2026-04/bundle.json's own
            // "sample_rate" field is 24000 - read from the manifest, never a literal here (rule 1).
            sampleRate = facts.sampleRate,
            voices = facts.voices,
            defaultVoiceId = facts.voices.first().id,
            // Honest-closed (class KDoc "STATUS"): no native speed/duration knob has been
            // identified in Pocket TTS's CLI/API (only `temperature` and `lsd_steps`, neither of
            // which is a speech-rate control) - never fabricate one (CLAUDE.md rule 2).
            parameters = emptyList(),
            assetPaths = facts.assetPaths,
            // No a-priori estimate: peak RAM depends on which precision (fp32/int8) and which
            // language variant (6-layer vs 24-layer, ~300 MB vs ~1.2 GB of flow_lm_main alone per
            // docs/research/pockettts-facts.md) a future loader picks - UNKNOWN is the honest
            // answer rather than a guess (rule 1).
            resourceCost = ResourceCost.UNKNOWN,
            // No continuous voice-embedding blend support is implemented here (STATUS above).
            supportsVoiceBlend = false,
        )

    /** Everything [bundleFacts] discovers about one bundle before a descriptor is built. */
    private data class BundleFacts(
        val bundleName: String,
        val sampleRate: Int,
        val voices: List<Voice>,
        val assetPaths: Map<String, String>,
    )

    companion object {
        const val ENGINE_ID = "pockettts"
        const val DISPLAY_NAME = "Pocket TTS"

        const val BUNDLE_METADATA_FILE = "bundle.json"
        const val TOKENIZER_FILE = "tokenizer.model"

        // The 5 ONNX graphs KevinAHM/pocket-tts-onnx-export produces per language bundle
        // (docs/research/pockettts-facts.md, sourced from that repo's README + export scripts).
        const val TEXT_CONDITIONER_STEM = "text_conditioner"
        const val MIMI_ENCODER_STEM = "mimi_encoder"
        const val FLOW_LM_MAIN_STEM = "flow_lm_main"
        const val FLOW_LM_FLOW_STEM = "flow_lm_flow"
        const val MIMI_DECODER_STEM = "mimi_decoder"

        // mimi_encoder/text_conditioner "remain FP32" per KevinAHM/pocket-tts-onnx's own README;
        // the other three graphs optionally also ship a quantized `_int8.onnx` sibling.
        private val FP32_ONLY_STEMS = listOf(TEXT_CONDITIONER_STEM, MIMI_ENCODER_STEM)
        private val QUANTIZABLE_STEMS = listOf(FLOW_LM_MAIN_STEM, FLOW_LM_FLOW_STEM, MIMI_DECODER_STEM)
        val ALL_GRAPH_STEMS = FP32_ONLY_STEMS + QUANTIZABLE_STEMS

        private const val ONNX_SUFFIX = ".onnx"
        private const val INT8_SUFFIX = "_int8.onnx"

        const val CONFIG_ASSET_KEY = "bundleConfig"
        const val TOKENIZER_ASSET_KEY = "tokenizer"

        private const val DEFAULT_VOICE_ID = "default"
    }
}
