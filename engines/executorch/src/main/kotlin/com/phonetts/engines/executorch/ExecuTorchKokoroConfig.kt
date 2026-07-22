package com.phonetts.engines.executorch

import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asFloatOrNull
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull

/**
 * Parses the `config.json` companion file [ExecuTorchKokoroEngine.inspect] fingerprints a bundle
 * by. VERIFIED (Hugging Face `software-mansion/react-native-executorch-kokoro/config.json`): the
 * real repo's file is exactly `{"modelName": "kokoro"}` — no `family`, no `sample_rate`, no speed
 * bounds. So, like `:engines:kokoro`'s `KokoroConfig`, this reads `model_name`/`family` as
 * alternate family markers and treats every other field as an optional override for a curated
 * bundle; a bundle shipping only the bare real-repo shape still fingerprints, falling back to this
 * engine's own verified/assumed constants for everything else (see `ExecuTorchKokoroEngine`).
 */
object ExecuTorchKokoroConfig {
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
        return Parsed(
            family = root?.get(KEY_FAMILY)?.asStringOrNull() ?: root?.get(KEY_MODEL_NAME)?.asStringOrNull(),
            sampleRate = root?.get(KEY_SAMPLE_RATE)?.asIntOrNull(),
            speedMin = root?.get(KEY_SPEED_MIN)?.asFloatOrNull(),
            speedMax = root?.get(KEY_SPEED_MAX)?.asFloatOrNull(),
            defaultVoiceId = root?.get(KEY_DEFAULT_VOICE)?.asStringOrNull(),
            defaultSpeed = root?.get(KEY_DEFAULT_SPEED)?.asFloatOrNull(),
        )
    }

    private const val KEY_FAMILY = "family"

    // VERIFIED: the real react-native-executorch-kokoro config.json key.
    private const val KEY_MODEL_NAME = "modelName"
    private const val KEY_SAMPLE_RATE = "sample_rate"
    private const val KEY_SPEED_MIN = "speed_min"
    private const val KEY_SPEED_MAX = "speed_max"
    private const val KEY_DEFAULT_VOICE = "default_voice"
    private const val KEY_DEFAULT_SPEED = "default_speed"
}
