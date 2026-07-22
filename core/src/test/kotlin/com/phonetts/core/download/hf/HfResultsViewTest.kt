package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HfResultsViewTest {
    private val results =
        listOf(
            HfModelSummary(id = "hexgrad/Kokoro-82M", likes = 900, downloads = 120_000, tags = listOf("onnx", "tts")),
            HfModelSummary(id = "rhasspy/piper-voices", likes = 50, downloads = 500_000, tags = listOf("piper")),
            HfModelSummary(id = "coqui/XTTS-v2", likes = 3_000, downloads = 10_000, tags = listOf("coqui", "tts")),
        )

    @Test
    fun sortsByMostDownloads() {
        val sorted = HfResultsView.sort(results, HfSortOption.MOST_DOWNLOADS)
        assertEquals(listOf("rhasspy/piper-voices", "hexgrad/Kokoro-82M", "coqui/XTTS-v2"), sorted.map { it.id })
    }

    @Test
    fun sortsByMostLikes() {
        val sorted = HfResultsView.sort(results, HfSortOption.MOST_LIKES)
        assertEquals(listOf("coqui/XTTS-v2", "hexgrad/Kokoro-82M", "rhasspy/piper-voices"), sorted.map { it.id })
    }

    @Test
    fun sortsByNameAscendingCaseInsensitively() {
        val sorted = HfResultsView.sort(results, HfSortOption.NAME_ASC)
        assertEquals(listOf("coqui/XTTS-v2", "hexgrad/Kokoro-82M", "rhasspy/piper-voices"), sorted.map { it.id })
    }

    @Test
    fun relevanceLeavesTheOriginalOrderUntouched() {
        assertEquals(results.map { it.id }, HfResultsView.sort(results, HfSortOption.RELEVANCE).map { it.id })
    }

    @Test
    fun availableTagsAreDerivedFromTheCurrentResultsNotAHardcodedList() {
        assertEquals(listOf("coqui", "onnx", "piper", "tts"), HfResultsView.availableTags(results))
    }

    @Test
    fun filteringByATagKeepsOnlyMatchingResults() {
        val filtered = HfResultsView.filterByTag(results, "tts")
        assertEquals(listOf("hexgrad/Kokoro-82M", "coqui/XTTS-v2"), filtered.map { it.id })
    }

    @Test
    fun blankOrNullTagMeansNoFilter() {
        assertEquals(results.map { it.id }, HfResultsView.filterByTag(results, null).map { it.id })
        assertEquals(results.map { it.id }, HfResultsView.filterByTag(results, "  ").map { it.id })
    }

    @Test
    fun applyFiltersThenSorts() {
        val filtered = HfResultsView.apply(results, HfSortOption.MOST_DOWNLOADS, tag = "tts")
        assertEquals(listOf("hexgrad/Kokoro-82M", "coqui/XTTS-v2"), filtered.map { it.id })
    }

    private val sizeEstimates =
        mapOf(
            // Kokoro known, largest of the two known.
            "hexgrad/Kokoro-82M" to HfSizeEstimate(knownBytes = 300_000_000L, unknownFileCount = 0),
            // Piper known, smallest.
            "rhasspy/piper-voices" to HfSizeEstimate(knownBytes = 60_000_000L, unknownFileCount = 0),
            // coqui/XTTS-v2 intentionally absent: not yet fetched.
        )

    @Test
    fun sortsByLargestSizeWithUnknownSizesLast() {
        val sorted = HfResultsView.sort(results, HfSortOption.LARGEST_SIZE, sizeEstimates)
        assertEquals(listOf("hexgrad/Kokoro-82M", "rhasspy/piper-voices", "coqui/XTTS-v2"), sorted.map { it.id })
    }

    @Test
    fun sortsBySmallestSizeWithUnknownSizesLast() {
        val sorted = HfResultsView.sort(results, HfSortOption.SMALLEST_SIZE, sizeEstimates)
        assertEquals(listOf("rhasspy/piper-voices", "hexgrad/Kokoro-82M", "coqui/XTTS-v2"), sorted.map { it.id })
    }

    @Test
    fun sortsByMostAndFewestParamsDerivedFromSize() {
        val most = HfResultsView.sort(results, HfSortOption.MOST_PARAMS, sizeEstimates)
        assertEquals(listOf("hexgrad/Kokoro-82M", "rhasspy/piper-voices", "coqui/XTTS-v2"), most.map { it.id })
        val fewest = HfResultsView.sort(results, HfSortOption.FEWEST_PARAMS, sizeEstimates)
        assertEquals(listOf("rhasspy/piper-voices", "hexgrad/Kokoro-82M", "coqui/XTTS-v2"), fewest.map { it.id })
    }

    @Test
    fun sizeDependentSortOptionsAreFlaggedAsNeedingSize() {
        assertTrue(HfSortOption.LARGEST_SIZE.needsSize())
        assertTrue(HfSortOption.SMALLEST_SIZE.needsSize())
        assertTrue(HfSortOption.MOST_PARAMS.needsSize())
        assertTrue(HfSortOption.FEWEST_PARAMS.needsSize())
        assertFalse(HfSortOption.MOST_DOWNLOADS.needsSize())
    }

    @Test
    fun filterBySizeKeepsOnlyResultsWithinBoundsAndDropsUnknown() {
        val filtered = HfResultsView.filterBySize(results, sizeEstimates, minBytes = 100_000_000L, maxBytes = null)
        assertEquals(listOf("hexgrad/Kokoro-82M"), filtered.map { it.id })
    }

    @Test
    fun filterBySizeWithNoBoundsIsANoOpAndKeepsUnknownResults() {
        val filtered = HfResultsView.filterBySize(results, sizeEstimates, minBytes = null, maxBytes = null)
        assertEquals(results.map { it.id }, filtered.map { it.id })
    }

    @Test
    fun filterByParamCountKeepsOnlyResultsWithinBoundsAndDropsUnknown() {
        val filtered = HfResultsView.filterByParamCount(results, sizeEstimates, minParams = null, maxParams = 1L)
        assertEquals(emptyList<String>(), filtered.map { it.id })
    }

    @Test
    fun applyWithSizeFilterAndSortComposesAllThree() {
        val filter = HfSizeParamFilter(minBytes = 1L)
        val filtered = HfResultsView.apply(results, HfSortOption.LARGEST_SIZE, tag = null, sizeEstimates, filter)
        assertEquals(listOf("hexgrad/Kokoro-82M", "rhasspy/piper-voices"), filtered.map { it.id })
    }
}
