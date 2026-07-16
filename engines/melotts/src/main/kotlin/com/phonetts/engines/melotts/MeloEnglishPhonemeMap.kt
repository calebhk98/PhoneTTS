package com.phonetts.engines.melotts

/**
 * Maps a single espeak-ng IPA codepoint to the closest [MeloSymbolTable] English symbol name.
 *
 * Real MeloTTS gets its English phonemes from `g2p_en` (a CMUdict lookup + a neural fallback)
 * emitting ARPAbet, not IPA. This module has neither CMUdict nor `g2p_en` available in Kotlin,
 * and the task explicitly allows routing through the shared espeak-backed
 * [com.phonetts.core.text.Phonemizer] instead (spec §5.2's shared frontend path) — so this is a
 * per-codepoint IPA -> ARPAbet-ish approximation, not the real g2p. It only walks one `Char` at a
 * time (like [com.phonetts.engines.piper.PiperFrontend]/KokoroFrontend), so it cannot reconstruct
 * multi-letter diphthongs/affricates espeak emits as separate codepoints (e.g. the FACE vowel
 * "eɪ" becomes two phonemes "eh"+"ih" here, not one "ey"); acceptable because perfect prosody is
 * out of scope (this ticket's bar is a valid, in-vocabulary `x`/`tone` sequence that runs the
 * real graph), but real pronunciation will be rougher than upstream MeloTTS's own g2p.
 */
internal object MeloEnglishPhonemeMap {
    const val PRIMARY_STRESS_MARK: Char = 'ˈ'
    const val SECONDARY_STRESS_MARK: Char = 'ˌ'
    const val LENGTH_MARK: Char = 'ː'

    private val CONSONANTS: Map<Char, String> =
        mapOf(
            'p' to "p", 'b' to "b", 't' to "t", 'd' to "d", 'k' to "k",
            'ɡ' to "g", 'g' to "g", 'f' to "f", 'v' to "V", 'θ' to "th",
            'ð' to "dh", 's' to "s", 'z' to "z", 'ʃ' to "sh", 'ʒ' to "zh",
            'h' to "hh", 'x' to "hh", 'm' to "m", 'n' to "n", 'ŋ' to "ng",
            'l' to "l", 'r' to "r", 'ɹ' to "r", 'w' to "w", 'j' to "y",
        )

    private val VOWELS: Map<Char, String> =
        mapOf(
            'i' to "iy", 'ɪ' to "ih", 'e' to "ey", 'ɛ' to "eh", 'æ' to "ae",
            'ɐ' to "ah", 'ʌ' to "ah", 'ɑ' to "aa", 'ɒ' to "aa", 'ɔ' to "ao",
            'o' to "ow", 'ʊ' to "uh", 'u' to "uw", 'ə' to "ah", 'ɜ' to "er",
            'ɚ' to "er", 'ɝ' to "er", 'a' to "aa",
        )

    /** The [MeloSymbolTable] symbol NAME for IPA codepoint [ch], or `"UNK"` if unmapped. */
    fun symbolFor(ch: Char): String = CONSONANTS[ch] ?: VOWELS[ch] ?: UNKNOWN_SYMBOL

    /** True if [ch] is one of the vowels this table knows — only vowels carry a stress/tone class. */
    fun isVowel(ch: Char): Boolean = VOWELS.containsKey(ch)

    private const val UNKNOWN_SYMBOL = "UNK"
}
