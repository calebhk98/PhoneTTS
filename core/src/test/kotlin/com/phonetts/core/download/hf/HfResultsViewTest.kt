package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
