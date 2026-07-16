package com.phonetts.engines.kokoro

import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asFloatOrNull
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull

/**
 * Parses Kokoro's `config.json` companion file — one of the two signals [KokoroEngine.inspect]
 * fingerprints a bundle by (the other being at least one `voices/<name>.bin` file present by
 * name; see [KokoroVoiceTable]). Reads it through
 * the shared, dependency-free `com.phonetts.engines.common.json.MiniJson` reader every engine
 * module already links against for exactly this kind of small companion file, rather than a
 * second hand-rolled parser private to this engine.
 *
 * Expected shape (all fields optional except where noted by [KokoroEngine]):
 * ```
 * {
 *   "family": "kokoro",
 *   "sample_rate": 24000,
 *   "speed_min": 0.5,
 *   "speed_max": 2.0,
 *   "default_voice": "af_heart",
 *   "default_speed": 1.0
 * }
 * ```
 */
object KokoroConfig {
    data class Parsed(
        val family: String?,
        val sampleRate: Int?,
        val speedMin: Float?,
        val speedMax: Float?,
        val defaultVoiceId: String?,
        val defaultSpeed: Float?,
    )

    fun parse(text: String): Parsed {
        val root = MiniJson.parse(text)?.asObjectOrNull()
        // The real onnx-community Kokoro export's config.json carries no "family" field — just
        // {"model_type": "style_text_to_speech_2"}. Fall back to model_type so a curated/sideloaded
        // real repo is recognized (KokoroEngine accepts either marker), while our own curated
        // "family": "kokoro" bundles keep working.
        return Parsed(
            family = root?.get(KEY_FAMILY)?.asStringOrNull() ?: root?.get(KEY_MODEL_TYPE)?.asStringOrNull(),
            sampleRate = root?.get(KEY_SAMPLE_RATE)?.asIntOrNull(),
            speedMin = root?.get(KEY_SPEED_MIN)?.asFloatOrNull(),
            speedMax = root?.get(KEY_SPEED_MAX)?.asFloatOrNull(),
            defaultVoiceId = root?.get(KEY_DEFAULT_VOICE)?.asStringOrNull(),
            defaultSpeed = root?.get(KEY_DEFAULT_SPEED)?.asFloatOrNull(),
        )
    }

    private const val KEY_FAMILY = "family"
    private const val KEY_MODEL_TYPE = "model_type"
    private const val KEY_SAMPLE_RATE = "sample_rate"
    private const val KEY_SPEED_MIN = "speed_min"
    private const val KEY_SPEED_MAX = "speed_max"
    private const val KEY_DEFAULT_VOICE = "default_voice"
    private const val KEY_DEFAULT_SPEED = "default_speed"
}
