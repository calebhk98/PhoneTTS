package com.phonetts.app.text

import android.content.Context
import android.util.Log
import com.phonetts.core.text.EspeakIpaNormalizer
import com.phonetts.core.text.Phonemizer

/**
 * The real, espeak-ng-backed [Phonemizer] (spec §5.2) shared by the Piper/KittenTTS/Kokoro
 * frontends: text -> IPA phoneme string, over JNI, via [EspeakNative]. See
 * docs/espeak-ng-integration.md for the native build this depends on.
 *
 * Never crashes the app: every failure mode (native lib missing, data files missing, espeak-ng
 * init failure, or an unexpected native-call exception) is caught and turns this into a
 * transparent delegate to [fallback] (a labelled [PassthroughPhonemizer] by default), logging a
 * warning instead of throwing (per this ticket's requirement - "the app never hard-crashes").
 *
 * All native calls are synchronized on [lock]: espeak-ng keeps mutable global state (current
 * voice, synthesis context) and is documented as not safe for concurrent calls across threads
 * (docs/research/espeak-ng.md §6.1).
 */
class EspeakPhonemizer(
    context: Context,
    private val fallback: Phonemizer = PassthroughPhonemizer(),
) : Phonemizer {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val available = initializeOrFallback()

    override fun phonemize(
        text: String,
        language: String,
    ): String {
        if (text.isEmpty()) return ""
        if (!available) return fallback.phonemize(text, language)

        val result =
            runCatching {
                synchronized(lock) { EspeakNative.nativeTextToPhonemesIpa(text, toEspeakVoice(language)) }
            }
        val raw = result.getOrNull()
        if (raw == null) {
            val cause = result.exceptionOrNull()
            Log.w(TAG, "espeak-ng phonemization failed for language '$language' -- falling back", cause)
            return fallback.phonemize(text, language)
        }
        return EspeakIpaNormalizer.normalize(raw)
    }

    private fun initializeOrFallback(): Boolean {
        if (!EspeakNative.isLibraryLoaded) {
            Log.w(TAG, "libphonetts_espeak.so did not load -- falling back to PassthroughPhonemizer")
            return false
        }
        val dataPath = EspeakDataInstaller(appContext).install()
        if (dataPath == null) {
            Log.w(TAG, "espeak-ng-data assets missing -- falling back to PassthroughPhonemizer")
            return false
        }
        return runInit(dataPath)
    }

    private fun runInit(dataPath: String): Boolean {
        val result = runCatching { synchronized(lock) { EspeakNative.nativeInit(dataPath) } }
        val sampleRate = result.getOrNull()
        if (result.isFailure || sampleRate == null || sampleRate < 0) {
            Log.w(TAG, "espeak_Initialize failed (result=$sampleRate) -- falling back", result.exceptionOrNull())
            return false
        }
        return true
    }

    // ASSUMPTION (unverified on-device): a plain language tag ("en", "es") needs mapping to an
    // espeak-ng voice name. espeak-ng accepts many forms directly (e.g. "es", "fr" resolve on
    // their own), so this only rewrites the one case the app is most likely to pass where
    // espeak-ng's bare default would differ from what English voices were trained against
    // ("en-us", per docs/research/model-facts.md). Anything else passes through unchanged and
    // relies on espeak-ng's own language-code resolution / its own fail-safe default voice.
    private fun toEspeakVoice(language: String): String {
        val mapped = LANGUAGE_TO_VOICE[language.lowercase()]
        return mapped ?: language.ifBlank { DEFAULT_VOICE }
    }

    private companion object {
        const val TAG = "EspeakPhonemizer"
        const val DEFAULT_VOICE = "en-us"
        val LANGUAGE_TO_VOICE = mapOf("en" to "en-us")
    }
}
