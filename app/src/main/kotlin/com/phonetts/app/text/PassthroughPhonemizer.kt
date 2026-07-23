package com.phonetts.app.text

import com.phonetts.core.text.Phonemizer

/**
 * Labelled no-op [Phonemizer]: returns text unchanged. Was the sole implementation before the
 * espeak-ng NDK binding landed (see docs/espeak-ng-integration.md); now it is [EspeakPhonemizer]'s
 * fallback, used only when the native library fails to load, its data files are missing, or
 * `espeak_Initialize` fails on a given device - never the primary path.
 *
 * Kept deliberately dumb: engines that phonemize will map few/no ids and produce silence/garbage
 * on this fallback, which is the honest, fail-closed behavior (spec rule 4's spirit) - degrade
 * loudly (a logged warning from [EspeakPhonemizer]) rather than silently mis-synthesizing.
 */
class PassthroughPhonemizer : Phonemizer {
    override fun phonemize(
        text: String,
        language: String,
    ): String = text
}
