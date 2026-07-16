package com.phonetts.app.text

import com.phonetts.core.text.Phonemizer

/**
 * Placeholder [Phonemizer]. The real one is an espeak-ng NDK binding (see
 * docs/research/espeak-ng.md) — the last native piece before the phoneme-based engines
 * (Piper/KittenTTS/Kokoro) produce correct audio. Until then this returns the text unchanged so
 * the app is wired and doesn't crash; engines that phonemize will simply map few/no ids and
 * produce silence/garbage, consistent with the still-unvalidated ONNX tensor names.
 */
class PassthroughPhonemizer : Phonemizer {
    override fun phonemize(text: String, language: String): String = text
}
