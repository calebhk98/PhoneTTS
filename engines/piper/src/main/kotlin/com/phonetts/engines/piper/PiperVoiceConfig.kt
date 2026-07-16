package com.phonetts.engines.piper

/**
 * The subset of a Piper `<voice>.onnx.json` sidecar this engine needs.
 *
 * Piper voices are VITS-family ONNX graphs; the sidecar is the model's own self-description
 * (spec §5.7's per-engine equivalent — [PiperEngine] turns this into the shared
 * [com.phonetts.core.model.ModelDescriptor], never the other way round). Fields:
 *  - `audio.sample_rate`      — required. Read the real value here; 22050 Hz is only the
 *    documented Piper default (docs/research/model-facts.md), never assumed silently.
 *  - `phoneme_id_map`         — required, non-empty. phoneme string -> one-or-more token ids.
 *  - `espeak.voice`           — optional espeak-ng language/voice code, e.g. "en-us".
 *  - `inference.length_scale` / `noise_scale` / `noise_w` — optional VITS inference knobs; the
 *    model's own defaults when present. [defaultLengthScale] is the anchor speed=1.0 maps to;
 *    see [PiperEngine] for how UI speed is routed onto it (length_scale is INVERSE to speed).
 */
internal data class PiperVoiceConfig(
    val sampleRate: Int,
    val phonemeIdMap: Map<String, List<Long>>,
    val language: String,
    val defaultLengthScale: Float,
    val noiseScale: Float,
    val noiseW: Float,
) {
    companion object {
        // Piper/VITS inference defaults when a sidecar omits an "inference" block entirely
        // (only used by forcedMatch()'s best-effort path, never by inspect()'s fail-closed one).
        const val DEFAULT_SAMPLE_RATE = 22_050
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_LENGTH_SCALE = 1.0f
        const val DEFAULT_NOISE_SCALE = 0.667f
        const val DEFAULT_NOISE_W = 0.8f

        private const val KEY_AUDIO = "audio"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_PHONEME_ID_MAP = "phoneme_id_map"
        private const val KEY_ESPEAK = "espeak"
        private const val KEY_ESPEAK_VOICE = "voice"
        private const val KEY_INFERENCE = "inference"
        private const val KEY_LENGTH_SCALE = "length_scale"
        private const val KEY_NOISE_SCALE = "noise_scale"
        private const val KEY_NOISE_W = "noise_w"

        /**
         * Parses a Piper sidecar. Returns null (never throws) if [json] is malformed OR is
         * missing either field that makes a sidecar recognizably Piper's: `audio.sample_rate`
         * and a non-empty `phoneme_id_map`. This is the fail-closed core of
         * [PiperEngine.inspect] — a bare/foreign JSON file must not be mistaken for a voice.
         */
        fun parse(json: String): PiperVoiceConfig? {
            val root = MiniJson.parse(json)?.asObjectOrNull() ?: return null
            val sampleRate = root[KEY_AUDIO]?.asObjectOrNull()?.get(KEY_SAMPLE_RATE)?.asIntOrNull() ?: return null
            val phonemeIdMap = parsePhonemeIdMap(root) ?: return null
            return PiperVoiceConfig(
                sampleRate = sampleRate,
                phonemeIdMap = phonemeIdMap,
                language =
                    root[KEY_ESPEAK]?.asObjectOrNull()?.get(KEY_ESPEAK_VOICE)?.asStringOrNull()
                        ?: DEFAULT_LANGUAGE,
                defaultLengthScale = inferenceFloat(root, KEY_LENGTH_SCALE) ?: DEFAULT_LENGTH_SCALE,
                noiseScale = inferenceFloat(root, KEY_NOISE_SCALE) ?: DEFAULT_NOISE_SCALE,
                noiseW = inferenceFloat(root, KEY_NOISE_W) ?: DEFAULT_NOISE_W,
            )
        }

        /** Best-effort defaults for [PiperEngine.forcedMatch] when a sidecar is absent/unparsable. */
        fun fallback(): PiperVoiceConfig =
            PiperVoiceConfig(
                sampleRate = DEFAULT_SAMPLE_RATE,
                phonemeIdMap = emptyMap(),
                language = DEFAULT_LANGUAGE,
                defaultLengthScale = DEFAULT_LENGTH_SCALE,
                noiseScale = DEFAULT_NOISE_SCALE,
                noiseW = DEFAULT_NOISE_W,
            )

        private fun parsePhonemeIdMap(root: Map<String, JsonValue>): Map<String, List<Long>>? {
            val raw = root[KEY_PHONEME_ID_MAP]?.asObjectOrNull() ?: return null
            if (raw.isEmpty()) return null
            return raw.mapValues { (_, ids) -> ids.asLongListOrEmpty() }
        }

        private fun inferenceFloat(
            root: Map<String, JsonValue>,
            key: String,
        ): Float? = root[KEY_INFERENCE]?.asObjectOrNull()?.get(key)?.asFloatOrNull()
    }
}
