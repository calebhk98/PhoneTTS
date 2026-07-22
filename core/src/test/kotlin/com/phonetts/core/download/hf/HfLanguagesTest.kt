package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals

class HfLanguagesTest {
    private val english =
        HfModelSummary(id = "hexgrad/Kokoro-82M", tags = listOf("text-to-speech", "en", "onnx"))
    private val spanish =
        HfModelSummary(id = "someone/spanish-tts", tags = listOf("text-to-speech", "es"))
    private val multilingual =
        HfModelSummary(id = "openbmb/VoxCPM2", tags = listOf("tts", "multilingual", "zh", "en"))
    private val untagged =
        HfModelSummary(id = "mystery/model", tags = listOf("safetensors", "region:us"))

    private val results = listOf(english, spanish, multilingual, untagged)

    @Test
    fun codesOfPicksOutIsoCodesAndTheMultilingualMarkerOnly() {
        assertEquals(listOf("en"), HfLanguages.codesOf(english))
        assertEquals(listOf("multilingual", "zh", "en"), HfLanguages.codesOf(multilingual))
        assertEquals(emptyList(), HfLanguages.codesOf(untagged))
    }

    @Test
    fun availableLanguagesAreDerivedFromResultsWithMultilingualLast() {
        // Specific languages sorted by display name (Chinese, English, Spanish), multilingual last.
        assertEquals(listOf("zh", "en", "es", "multilingual"), HfLanguages.availableLanguages(results))
    }

    @Test
    fun displayNameMapsKnownCodesAndFallsBackToTheRawCode() {
        assertEquals("English", HfLanguages.displayName("en"))
        assertEquals("Multilingual", HfLanguages.displayName("multilingual"))
        assertEquals("qqq", HfLanguages.displayName("qqq"))
    }

    @Test
    fun filteringByASpecificLanguageAlsoKeepsMultilingualModels() {
        val english = HfLanguages.filterByLanguage(results, "en")
        assertEquals(listOf("hexgrad/Kokoro-82M", "openbmb/VoxCPM2"), english.map { it.id })
    }

    @Test
    fun filteringByMultilingualKeepsOnlyExplicitlyMultilingualModels() {
        val filtered = HfLanguages.filterByLanguage(results, "multilingual")
        assertEquals(listOf("openbmb/VoxCPM2"), filtered.map { it.id })
    }

    @Test
    fun blankOrNullLanguageMeansNoFilter() {
        assertEquals(results.map { it.id }, HfLanguages.filterByLanguage(results, null).map { it.id })
        assertEquals(results.map { it.id }, HfLanguages.filterByLanguage(results, "  ").map { it.id })
    }
}
