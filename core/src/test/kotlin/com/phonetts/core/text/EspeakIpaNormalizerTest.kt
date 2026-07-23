package com.phonetts.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The deterministic, pure-Kotlin slice of the espeak-ng phoneme pipeline (spec §9 - test the
 * plumbing, not the audio). The native call itself (`espeak_jni.cpp` -> [EspeakIpaNormalizer])
 * can't run on a JVM-only CI job; this covers everything downstream of it.
 */
class EspeakIpaNormalizerTest {
    @Test
    fun stripsCombiningTieBarsSoAffricatesBecomeTwoCodepoints() {
        // "t͡ʃ" (t + U+0361 tie bar + ʃ) -> "tʃ", two independently-lookupable phonemes.
        assertEquals("tʃ", EspeakIpaNormalizer.normalize("t͡ʃ"))
        assertEquals("dʒ", EspeakIpaNormalizer.normalize("d͜ʒ"))
    }

    @Test
    fun collapsesWhitespaceRunsToASingleSpace() {
        assertEquals("hɛ lə", EspeakIpaNormalizer.normalize("hɛ  \n\t lə"))
    }

    @Test
    fun trimsLeadingAndTrailingWhitespace() {
        assertEquals("wɜːd", EspeakIpaNormalizer.normalize("  wɜːd  \n"))
    }

    @Test
    fun leavesStressAndLengthMarksUntouched() {
        // ˈ (primary stress), ˌ (secondary stress), ː (length) are real phoneme-map entries,
        // not decorations - must survive normalization unchanged.
        assertEquals("ˈhɛloʊ ˌwɜːld", EspeakIpaNormalizer.normalize("ˈhɛloʊ ˌwɜːld"))
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", EspeakIpaNormalizer.normalize(""))
        assertEquals("", EspeakIpaNormalizer.normalize("   "))
    }

    @Test
    fun joinsMultipleClausesSeparatedBySpaceUnchanged() {
        // Simulates espeak_jni.cpp joining per-clause phoneme strings with a space separator.
        val clause1 = "hɛloʊ"
        val clause2 = "wɜːld"
        assertEquals("hɛloʊ wɜːld", EspeakIpaNormalizer.normalize("$clause1 $clause2"))
    }
}
