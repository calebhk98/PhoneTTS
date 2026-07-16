package com.phonetts.integration

import com.phonetts.core.text.EspeakIpaNormalizer
import com.phonetts.core.text.Phonemizer

/**
 * The JVM twin of the app's `EspeakPhonemizer`: same [Phonemizer] seam, same IPA output, but it
 * shells out to the system `espeak-ng` binary instead of calling the NDK build via JNI. Crucially
 * it runs the raw espeak IPA through the app's OWN [EspeakIpaNormalizer] (the deterministic
 * pure-Kotlin cleanup the real phonemizer uses), so the phoneme string an engine's frontend sees
 * here is what it would see on-device.
 */
class EspeakCliPhonemizer : Phonemizer {
    override fun phonemize(
        text: String,
        language: String,
    ): String {
        val voice = espeakVoice(language)
        val process =
            ProcessBuilder("espeak-ng", "-q", "--ipa", "-v", voice, text)
                .redirectErrorStream(false)
                .start()
        val raw = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return EspeakIpaNormalizer.normalize(raw)
    }

    // Map a model's language tag (e.g. "en_US", "en-us", "en") to an espeak-ng voice name.
    private fun espeakVoice(language: String): String {
        val normalized = language.lowercase().replace('_', '-')
        return if (normalized.startsWith("en") && !normalized.contains('-')) "en-us" else normalized
    }
}
