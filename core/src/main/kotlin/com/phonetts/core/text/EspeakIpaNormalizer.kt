package com.phonetts.core.text

/**
 * Cleans up raw IPA text returned by espeak-ng's `espeak_TextToPhonemes` (IPA phoneme mode)
 * before it is handed to a model's own phoneme->id mapping. Pure, deterministic, and has no
 * Android/native dependency, so it lives here rather than in the `:app` JNI wrapper - the JNI
 * layer's only job is "call espeak-ng, hand back the raw string per clause" (see
 * docs/espeak-ng-integration.md); everything after that is testable on any JVM, which is why
 * this class - not the JNI wrapper itself - is unit tested.
 *
 * The engines that consume [Phonemizer] output (Piper/KittenTTS/Kokoro frontends, spec §5.2)
 * walk the returned string one Kotlin `Char` at a time and look each one up in a per-model
 * phoneme table (that table is per-model, never shared - spec §1 SSOT rule). Two espeak-ng IPA
 * output quirks would break a one-codepoint-per-symbol walk if left in:
 *
 *  - **Tie bars** (U+0361 COMBINING DOUBLE INVERTED BREVE, U+035C COMBINING DOUBLE BREVE BELOW):
 *    espeak-ng sometimes joins affricates/diphthongs with a tie bar (e.g. `"t͡ʃ"`). Stripping the
 *    tie bar leaves the two base phonemes (`"t"`, `"ʃ"`) as separate codepoints - exactly what a
 *    one-codepoint-per-symbol phoneme table expects, and how real Piper voice phoneme maps are
 *    keyed (single IPA symbols, no ties).
 *  - **Whitespace runs**: espeak-ng returns one phoneme string per clause (see the loop in
 *    `espeak_jni.cpp`); joining clauses with a separator plus espeak's own word-separating
 *    spaces can produce consecutive spaces/newlines. Collapsed to a single U+0020 space, matching
 *    the `" "` (single space) word-boundary entry Piper's `phoneme_id_map` uses.
 *
 * ASSUMPTION (unverified without a real device + real voice phoneme maps, flagged per repo
 * convention): stress marks (`ˈ`, `ˌ`) and length marks (`ː`) are left untouched because they are
 * themselves legitimate entries in Piper-family phoneme maps, not decorations to strip.
 */
object EspeakIpaNormalizer {
    private const val TIE_BAR_ABOVE = '͡'
    private const val TIE_BAR_BELOW = '͜'
    private val WHITESPACE_RUN = Regex("\\s+")

    fun normalize(raw: String): String {
        val withoutTieBars = raw.filterNot { it == TIE_BAR_ABOVE || it == TIE_BAR_BELOW }
        return withoutTieBars.replace(WHITESPACE_RUN, " ").trim()
    }
}
