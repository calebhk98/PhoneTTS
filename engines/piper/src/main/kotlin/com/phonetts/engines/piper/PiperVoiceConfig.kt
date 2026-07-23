package com.phonetts.engines.piper

import com.phonetts.engines.common.json.JsonValue
import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asFloatOrNull
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asLongListOrEmpty
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull

/**
 * The subset of a Piper `<voice>.onnx.json` sidecar this engine needs.
 *
 * Piper voices are VITS-family ONNX graphs; the sidecar is the model's own self-description
 * (spec §5.7's per-engine equivalent - [PiperEngine] turns this into the shared
 * [com.phonetts.core.model.ModelDescriptor], never the other way round). Fields:
 *  - `audio.sample_rate`      - required. Read the real value here; 22050 Hz is only the
 *    documented Piper default (docs/research/model-facts.md), never assumed silently.
 *  - `phoneme_id_map`         - required, non-empty. phoneme string -> one-or-more token ids.
 *  - `espeak.voice`           - optional espeak-ng language/voice code, e.g. "en-us".
 *  - `inference.length_scale` / `noise_scale` / `noise_w` - optional VITS inference knobs; the
 *    model's own defaults when present. [defaultLengthScale] is the anchor speed=1.0 maps to;
 *    see [PiperEngine] for how UI speed is routed onto it (length_scale is INVERSE to speed).
 *  - `num_speakers` / `speaker_id_map` - optional. A single-speaker voice omits them (or sets
 *    `num_speakers` to 1); a multi-speaker VITS graph (VCTK, LibriTTS, L2Arctic, …) declares
 *    `num_speakers > 1` and a `speaker_id_map` of speaker-name -> integer `sid`. Those graphs have
 *    an EXTRA required `sid` input, so [PiperEngine] must feed one - see [speakers] and
 *    [isMultiSpeaker]. Without it the ONNX session rejects the run, which is exactly why the
 *    multi-speaker Piper voices previously failed to synthesize.
 *  - `phoneme_type` / `num_languages` / `language_id_map` / `prosody_id_map` - the piper-plus
 *    multilingual markers (issue #110). A piper-plus graph needs EXTRA `language_id` and `prosody`
 *    inputs on top of the sid this engine already knows how to feed; this engine feeds only the
 *    fixed input/input_lengths/scales[/sid] VITS contract, so such a graph crashes at
 *    `session.run`. These markers set [declaresUnfedGraphInputs] so [PiperEngine.inspect] fails
 *    closed on them (CLAUDE.md rule 4) instead of claiming-then-crashing.
 */
internal data class PiperVoiceConfig(
    val sampleRate: Int,
    val phonemeIdMap: Map<String, List<Long>>,
    val language: String,
    val defaultLengthScale: Float,
    val noiseScale: Float,
    val noiseW: Float,
    val numSpeakers: Int,
    val speakerIdMap: Map<String, Int>,
    val declaresUnfedGraphInputs: Boolean = false,
) {
    /** True when this voice graph carries more than one speaker and therefore needs a `sid` input. */
    val isMultiSpeaker: Boolean get() = numSpeakers > 1

    /**
     * The ordered, selectable speakers for a multi-speaker graph - each a (name, `sid`) pair the
     * UI renders as its own [com.phonetts.core.engine.Voice]. Empty for a single-speaker voice
     * (which needs no `sid`). Prefers the declared `speaker_id_map`; if a graph reports
     * `num_speakers > 1` but ships no name map, speakers are the bare indices `0..num_speakers-1`
     * so every speaker is still reachable (discovered, never assumed - CLAUDE.md rule 1).
     */
    fun speakers(): List<PiperSpeaker> =
        when {
            !isMultiSpeaker -> emptyList()
            speakerIdMap.isNotEmpty() -> namedSpeakers()
            else -> (0 until numSpeakers).map { PiperSpeaker(it.toString(), it) }
        }

    private fun namedSpeakers(): List<PiperSpeaker> =
        speakerIdMap.entries.sortedBy { it.value }.map { PiperSpeaker(it.key, it.value) }

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
        private const val KEY_NUM_SPEAKERS = "num_speakers"
        private const val KEY_SPEAKER_ID_MAP = "speaker_id_map"
        private const val SINGLE_SPEAKER = 1

        // issue #110 piper-plus multilingual markers: a graph carrying any of these needs a
        // language_id and/or prosody input this engine does not feed (see [declaresUnfedGraphInputs]).
        private const val KEY_PHONEME_TYPE = "phoneme_type"
        private const val PHONEME_TYPE_MULTILINGUAL = "multilingual"
        private const val KEY_NUM_LANGUAGES = "num_languages"
        private const val KEY_LANGUAGE_ID_MAP = "language_id_map"
        private const val KEY_PROSODY_ID_MAP = "prosody_id_map"
        private const val KEY_PROSODY_NUM_SYMBOLS = "prosody_num_symbols"
        private const val SINGLE_LANGUAGE = 1

        /**
         * Parses a Piper sidecar. Returns null (never throws) if [json] is malformed OR is
         * missing either field that makes a sidecar recognizably Piper's: `audio.sample_rate`
         * and a non-empty `phoneme_id_map`. This is the fail-closed core of
         * [PiperEngine.inspect] - a bare/foreign JSON file must not be mistaken for a voice.
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
                numSpeakers = root[KEY_NUM_SPEAKERS]?.asIntOrNull() ?: SINGLE_SPEAKER,
                speakerIdMap = parseSpeakerIdMap(root),
                declaresUnfedGraphInputs = declaresUnfedGraphInputs(root),
            )
        }

        // True when the sidecar declares graph inputs this engine cannot feed (issue #110): the
        // piper-plus multilingual family adds a language_id and/or prosody input on top of the
        // input/input_lengths/scales[/sid] contract this engine feeds, so such a graph is rejected
        // at inspect() rather than claimed-then-crashed. Any one marker is enough - an OR, so a
        // future variant that adds just one of them is still caught.
        private fun declaresUnfedGraphInputs(root: Map<String, JsonValue>): Boolean {
            val phonemeType = root[KEY_PHONEME_TYPE]?.asStringOrNull()
            if (phonemeType == PHONEME_TYPE_MULTILINGUAL) return true
            if ((root[KEY_NUM_LANGUAGES]?.asIntOrNull() ?: SINGLE_LANGUAGE) > SINGLE_LANGUAGE) return true
            if (root[KEY_LANGUAGE_ID_MAP]?.asObjectOrNull()?.isNotEmpty() == true) return true
            if (root[KEY_PROSODY_ID_MAP]?.asObjectOrNull()?.isNotEmpty() == true) return true
            return (root[KEY_PROSODY_NUM_SYMBOLS]?.asIntOrNull() ?: 0) > 0
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
                numSpeakers = SINGLE_SPEAKER,
                speakerIdMap = emptyMap(),
            )

        // speaker_id_map is a flat { "<speaker name>": <int sid> } object; drop any non-integer
        // value rather than failing the whole parse (a malformed entry must not sink a good voice).
        private fun parseSpeakerIdMap(root: Map<String, JsonValue>): Map<String, Int> {
            val raw = root[KEY_SPEAKER_ID_MAP]?.asObjectOrNull() ?: return emptyMap()
            return raw.mapNotNull { (name, id) -> id.asIntOrNull()?.let { name to it } }.toMap()
        }

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

/** One selectable speaker of a multi-speaker Piper graph: its display [name] and its integer [sid]. */
internal data class PiperSpeaker(
    val name: String,
    val sid: Int,
)
