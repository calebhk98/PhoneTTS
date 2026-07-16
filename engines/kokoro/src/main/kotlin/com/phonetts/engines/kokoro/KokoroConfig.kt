package com.phonetts.engines.kokoro

/**
 * Parses Kokoro's `config.json` companion file — one of the two side files [KokoroEngine.inspect]
 * fingerprints a bundle by (the other being [KokoroVoiceTable]'s `voices.json`). This is a
 * minimal hand-rolled parser for our own fixed, flat manifest shape (a JSON object with a small,
 * known set of string/number fields) — NOT a general-purpose JSON parser. Pulling in a JSON
 * library was avoided on purpose to keep this module's dependency surface at just `:core` +
 * coroutines, per its build file.
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

    fun parse(text: String): Parsed =
        Parsed(
            family = stringField(text, "family"),
            sampleRate = numberField(text, "sample_rate")?.toInt(),
            speedMin = numberField(text, "speed_min"),
            speedMax = numberField(text, "speed_max"),
            defaultVoiceId = stringField(text, "default_voice"),
            defaultSpeed = numberField(text, "default_speed"),
        )

    private fun stringField(
        text: String,
        key: String,
    ): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)

    private fun numberField(
        text: String,
        key: String,
    ): Float? = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
}
