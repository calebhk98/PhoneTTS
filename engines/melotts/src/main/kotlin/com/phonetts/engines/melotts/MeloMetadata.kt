package com.phonetts.engines.melotts

import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull

/**
 * Parses MeloTTS's `metadata.json` companion file (MiaoMint/MeloTTS-ONNX `onnx_exports/en_v2`),
 * e.g. `{"model_type":"melo-vits","language_code":"en","add_blank":1,"n_speakers":5,
 * "sample_rate":44100,"speaker_id":0,"lang_id":2,"tone_start":7,...}`. This is the SSOT for the
 * facts [MeloEngine.inspect] fingerprints the bundle by and builds its descriptor from - the
 * sample rate, voice count, and default speaker come from HERE, never a hardcoded literal.
 */
object MeloMetadata {
    data class Parsed(
        val modelType: String?,
        val comment: String?,
        val languageCode: String?,
        val nSpeakers: Int?,
        val sampleRate: Int?,
        val speakerId: Int?,
    ) {
        /** True if this metadata identifies a melo-vits export - the fingerprint [MeloEngine] fails closed on. */
        fun isMeloVits(): Boolean =
            modelType.equals(MODEL_TYPE_MARKER, ignoreCase = true) ||
                comment?.contains(COMMENT_MARKER, ignoreCase = true) == true
    }

    /** Parses [text] as MeloTTS metadata, or null on malformed JSON (fail closed, spec §9.1). */
    fun parse(text: String): Parsed? {
        val root = MiniJson.parse(text)?.asObjectOrNull() ?: return null
        return Parsed(
            modelType = root[KEY_MODEL_TYPE]?.asStringOrNull(),
            comment = root[KEY_COMMENT]?.asStringOrNull(),
            languageCode = root[KEY_LANGUAGE_CODE]?.asStringOrNull(),
            nSpeakers = root[KEY_N_SPEAKERS]?.asIntOrNull(),
            sampleRate = root[KEY_SAMPLE_RATE]?.asIntOrNull(),
            speakerId = root[KEY_SPEAKER_ID]?.asIntOrNull(),
        )
    }

    private const val KEY_MODEL_TYPE = "model_type"
    private const val KEY_COMMENT = "comment"
    private const val KEY_LANGUAGE_CODE = "language_code"
    private const val KEY_N_SPEAKERS = "n_speakers"
    private const val KEY_SAMPLE_RATE = "sample_rate"
    private const val KEY_SPEAKER_ID = "speaker_id"

    private const val MODEL_TYPE_MARKER = "melo-vits"
    private const val COMMENT_MARKER = "melo"
}
