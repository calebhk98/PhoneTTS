package com.phonetts.engines.inflect

import com.phonetts.engines.common.json.JsonValue
import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull

/**
 * The facts this engine reads out of an Inflect v2 export's `config.json` - the SSOT for the
 * descriptor it builds (CLAUDE.md rule 1: a sample rate is read from the model's own files, never
 * typed as a literal in the engine).
 *
 * [parse] is the fingerprint AND the fact source in one: it returns null unless the document
 * genuinely declares Inflect v2's own [FORMAT_MARKER] and carries every field the descriptor
 * needs. That is what makes `inspect()` fail closed (rule 4) - a `config.json` from some other
 * VITS derivative parses fine as JSON but has no `"format": "inflect_v2_inference_config_v1"`,
 * so it is refused rather than guessed at.
 *
 * VERIFIED against the real published files (both fetched and read at authoring time):
 * `owensong/Inflect-Micro-v2-ONNX/config.json` and `owensong/Inflect-Nano-v2-ONNX/config.json`.
 */
internal data class InflectConfig(
    /** `data.sampling_rate` - 24000 in both published exports, but read, not assumed. */
    val sampleRate: Int,
    /** `model.inter_channels` - the latent width (192 Micro / 128 Nano). */
    val latentChannels: Int,
    /** `model.hidden_channels` - the text-encoder width (96 Micro / 72 Nano). */
    val hiddenChannels: Int,
) {
    /**
     * Which published Inflect v2 size this config describes, by its architecture fingerprint.
     * A config that matches neither is still a valid Inflect v2 export (the format marker said so)
     * - it just gets the family label rather than a size claim this engine cannot substantiate.
     */
    val variant: InflectVariant
        get() =
            InflectVariant.entries.firstOrNull {
                it.latentChannels == latentChannels && it.hiddenChannels == hiddenChannels
            } ?: InflectVariant.UNKNOWN

    companion object {
        /**
         * The `"format"` value every Inflect v2 inference config declares. Verified present in both
         * published ONNX exports; it is the engine's whole fingerprint, so nothing else may claim
         * an Inflect bundle and no non-Inflect bundle can be claimed by accident.
         */
        const val FORMAT_MARKER = "inflect_v2_inference_config_v1"

        private const val FORMAT_KEY = "format"
        private const val DATA_KEY = "data"
        private const val MODEL_KEY = "model"
        private const val SAMPLING_RATE_KEY = "sampling_rate"
        private const val INTER_CHANNELS_KEY = "inter_channels"
        private const val HIDDEN_CHANNELS_KEY = "hidden_channels"

        /**
         * Parses an Inflect v2 `config.json`, or null if [text] is malformed, is not an Inflect v2
         * config, or is missing any fact the descriptor needs. Never throws and never guesses a
         * missing value - "not mine" and "cannot be established with confidence" are the same
         * answer here (spec §6.2).
         */
        fun parse(text: String): InflectConfig? {
            val root = MiniJson.parse(text)?.asObjectOrNull() ?: return null
            if (root[FORMAT_KEY]?.asStringOrNull() != FORMAT_MARKER) return null

            val sampleRate = positiveInt(root[DATA_KEY], SAMPLING_RATE_KEY)
            val latentChannels = positiveInt(root[MODEL_KEY], INTER_CHANNELS_KEY)
            val hiddenChannels = positiveInt(root[MODEL_KEY], HIDDEN_CHANNELS_KEY)
            if (sampleRate == null || latentChannels == null || hiddenChannels == null) return null

            return InflectConfig(sampleRate, latentChannels, hiddenChannels)
        }

        /** [key] inside the [section] object, but only when it is genuinely a positive integer. */
        private fun positiveInt(
            section: JsonValue?,
            key: String,
        ): Int? = section?.asObjectOrNull()?.get(key)?.asIntOrNull()?.takeIf { it > 0 }
    }
}

/**
 * The published Inflect v2 sizes, each identified by the architecture widths in its own
 * `config.json` rather than by a repo name or a file size - so a bundle sideloaded under any
 * folder name is still labelled correctly, and a future size this engine has never seen is
 * labelled honestly as [UNKNOWN] instead of being mislabelled as one of these.
 *
 * [peakRamMebibytes] is an a-priori estimate only (issue #38), scaled from the deployable FP32
 * weight footprints the model cards publish (37.53 MB Micro / 15.97 MB Nano); the app refines it
 * from observed peak RAM once a model has actually been loaded.
 */
internal enum class InflectVariant(
    val latentChannels: Int,
    val hiddenChannels: Int,
    val displayName: String,
    val peakRamMebibytes: Long,
) {
    MICRO_V2(
        latentChannels = 192,
        hiddenChannels = 96,
        displayName = "Inflect Micro v2 (English)",
        peakRamMebibytes = 160,
    ),
    NANO_V2(
        latentChannels = 128,
        hiddenChannels = 72,
        displayName = "Inflect Nano v2 (English)",
        peakRamMebibytes = 100,
    ),

    /**
     * A genuine Inflect v2 export (the format marker matched) whose widths match no size this
     * engine has verified. It runs identically - the graph contract is the same - so only the
     * label and the RAM estimate fall back to the family's.
     */
    UNKNOWN(latentChannels = 0, hiddenChannels = 0, displayName = "Inflect v2 (English)", peakRamMebibytes = 160),
}
