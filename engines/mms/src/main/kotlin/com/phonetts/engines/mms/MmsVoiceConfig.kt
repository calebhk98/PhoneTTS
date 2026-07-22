package com.phonetts.engines.mms

import com.phonetts.engines.common.json.JsonValue
import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull

/**
 * The subset of a `Xenova/mms-tts-*` bundle's `config.json` this engine needs (the model's own
 * self-description, spec §5.7's per-engine equivalent — never the other way round).
 *
 * VALIDATED against the real `Xenova/mms-tts-eng` and `Xenova/mms-tts-ara` repos on Hugging Face
 * (2026-07-22): both ship an identical shape —
 * ```json
 * {
 *   "_name_or_path": "facebook/mms-tts-eng",
 *   "model_type": "vits",
 *   "sampling_rate": 16000,
 *   "num_speakers": 1,
 *   "speaking_rate": 1.0,
 *   ...
 * }
 * ```
 *  - `model_type` — required, must be exactly `"vits"` (case-insensitive): MMS is a VITS export,
 *    but plenty of OTHER VITS exports exist (Piper among them) that are NOT MMS, so this alone is
 *    never enough to claim a bundle (see [markerPresent]).
 *  - `_name_or_path` — the base HF model id the ONNX weights were exported from. Every real MMS
 *    export we found preserves `facebook/mms-tts-<lang>` here; combined with `model_type == vits`
 *    this is the fail-closed MMS signature [MmsEngine.inspect] requires (spec §9.1) — a foreign
 *    VITS bundle's `_name_or_path` will not contain "mms-tts".
 *  - `sampling_rate` — required. MMS is 16000 Hz in every repo we checked, but this is READ, never
 *    hardcoded outside [MmsEngine.forcedMatch]'s best-effort fallback (CLAUDE.md rule 1).
 *  - `speaking_rate` is a Python-side generation-config default baked into the export at convert
 *    time — NOT a runtime graph input (confirmed by loading the real ONNX graph, see
 *    [MmsEngine]'s KDoc), so it is deliberately not read here: there is nothing for this engine to
 *    route a speed knob to (CLAUDE.md rule 2).
 */
internal data class MmsModelConfig(
    val modelType: String,
    val nameOrPath: String,
    val samplingRate: Int,
) {
    companion object {
        private const val KEY_MODEL_TYPE = "model_type"
        private const val KEY_NAME_OR_PATH = "_name_or_path"
        private const val KEY_SAMPLING_RATE = "sampling_rate"
        private const val VITS_MODEL_TYPE = "vits"

        /** The substring an MMS export's `_name_or_path` carries; the other half of the fail-closed check. */
        private const val MMS_MARKER = "mms-tts"

        /**
         * Parses [json], or returns null (never throws) if it is malformed or missing
         * `model_type`/`sampling_rate` — the fields every real config.json carries.
         */
        fun parse(json: String): MmsModelConfig? {
            val root = MiniJson.parse(json)?.asObjectOrNull() ?: return null
            val modelType = root[KEY_MODEL_TYPE]?.asStringOrNull() ?: return null
            val samplingRate = root[KEY_SAMPLING_RATE]?.asIntOrNull() ?: return null
            if (samplingRate <= 0) return null
            return MmsModelConfig(
                modelType = modelType,
                nameOrPath = root[KEY_NAME_OR_PATH]?.asStringOrNull() ?: "",
                samplingRate = samplingRate,
            )
        }
    }

    /** True only if this config is BOTH a VITS export AND self-describes as an MMS one (see class KDoc). */
    val isMmsVits: Boolean
        get() {
            val isVits = modelType.equals(VITS_MODEL_TYPE, ignoreCase = true)
            val isMms = nameOrPath.contains(MMS_MARKER, ignoreCase = true)
            return isVits && isMms
        }
}

/**
 * The subset of a `Xenova/mms-tts-*` bundle's `tokenizer_config.json` [MmsFrontend] needs.
 *
 * VALIDATED against `Xenova/mms-tts-eng`/`Xenova/mms-tts-ara` (2026-07-22):
 * ```json
 * {
 *   "add_blank": true,
 *   "is_uroman": false,
 *   "language": "eng",
 *   "normalize": true,
 *   "pad_token": "k",
 *   "phonemize": false,
 *   "tokenizer_class": "VitsTokenizer"
 * }
 * ```
 * Cross-checked against the shipped `tokenizer.json`'s Rust-tokenizers `normalizer` pipeline
 * (Lowercase -> drop any character outside the vocabulary -> strip leading/trailing whitespace ->
 * intersperse the `pad_token` before every kept character AND once more at the end), which is
 * exactly [MmsFrontend.toModelInput]'s algorithm when `add_blank` is true.
 *
 * LIMITATION (not implemented, documented rather than silently guessed — CLAUDE.md rule 4's
 * spirit applied to text preprocessing): `is_uroman: true` (some non-Latin-script MMS languages
 * romanize with the external `uroman` tool before tokenizing) and `phonemize: true` are NOT
 * implemented — there is no offline pure-Kotlin uroman/espeak-for-arbitrary-language port
 * available. Both `eng` and `ara` (checked) carry `is_uroman: false`, `phonemize: false` — MMS
 * ships several hundred languages with a DIRECT script vocabulary (Arabic, Cyrillic, Devanagari,
 * etc. all have their own `vocab.json` entries, same as `ara` above), so this is the common case,
 * not a rare corner. A bundle whose `tokenizer_config.json` declares `is_uroman`/`phonemize` true
 * still loads (this is a text-preprocessing gap, not an identification failure — rule 4 is about
 * `inspect()`, not runtime text quality); unrecognized characters are simply dropped, the same
 * fail-soft behaviour Piper's and Kokoro's own frontends use for out-of-vocabulary symbols.
 */
internal data class MmsTokenizerConfig(
    val addBlank: Boolean,
    val language: String,
    val padToken: String?,
) {
    companion object {
        private const val KEY_ADD_BLANK = "add_blank"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PAD_TOKEN = "pad_token"

        /** Best-effort defaults ([MmsEngine.forcedMatch] when this optional file is absent/unparsable). */
        val FALLBACK = MmsTokenizerConfig(addBlank = true, language = "und", padToken = null)

        /** Parses [json], or null (never throws) if malformed. This file is optional — see [MmsEngine]. */
        fun parse(json: String): MmsTokenizerConfig? {
            val root = MiniJson.parse(json)?.asObjectOrNull() ?: return null
            return MmsTokenizerConfig(
                addBlank = root[KEY_ADD_BLANK].asBoolOrNull() ?: FALLBACK.addBlank,
                language = root[KEY_LANGUAGE]?.asStringOrNull() ?: FALLBACK.language,
                padToken = root[KEY_PAD_TOKEN]?.asStringOrNull(),
            )
        }
    }
}

/**
 * A `vocab.json` grapheme -> token-id table: MMS's OWN character vocabulary (SSOT — read from the
 * bundle, never a hardcoded alphabet table, CLAUDE.md rule 1). Flat `{"<char>": <id>}`, e.g.
 * `{" ": 19, "a": 26, ...}` for `eng`, or the Arabic-script equivalent for `ara` — the same parser
 * handles either because it assumes nothing about which characters appear.
 */
internal object MmsVocab {
    /** Parses [json] into a char -> id map, or null if malformed / not a flat string->number object. */
    fun parse(json: String): Map<String, Long>? {
        val root = MiniJson.parse(json)?.asObjectOrNull() ?: return null
        if (root.isEmpty()) return null
        val entries = root.mapNotNull { (symbol, id) -> id.asIntOrNull()?.let { symbol to it.toLong() } }
        if (entries.isEmpty()) return null
        return entries.toMap()
    }
}

// MiniJson exposes asIntOrNull/asFloatOrNull/asStringOrNull but no boolean accessor (issue: none of
// the other engine modules' sidecars needed one yet) -- added here, module-local, rather than
// touching the shared :engines:common reader for one caller.
private fun JsonValue?.asBoolOrNull(): Boolean? = (this as? JsonValue.JsonBool)?.value
