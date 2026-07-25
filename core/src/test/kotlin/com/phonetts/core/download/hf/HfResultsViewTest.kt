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

    @Test
    fun frequentTagsDropBoilerplateAndLanguagesAndRankByFrequency() {
        val tagged =
            listOf(
                HfModelSummary(id = "a", tags = listOf("tts", "onnx", "en", "region:us", "license:mit")),
                HfModelSummary(id = "b", tags = listOf("tts", "safetensors", "es", "arxiv:1234.5678")),
                HfModelSummary(id = "c", tags = listOf("tts", "onnx", "multilingual")),
            )
        // "tts" (3) then "onnx" (2), then the singletons alphabetically. No language codes
        // (en/es/multilingual) and no namespaced boilerplate (region:/license:/arxiv:).
        assertEquals(listOf("tts", "onnx", "safetensors"), HfResultsView.frequentTags(tagged, limit = 3))
    }

    @Test
    fun frequentTagsHonorTheLimit() {
        val tagged = listOf(HfModelSummary(id = "a", tags = listOf("one", "two", "three", "four")))
        assertEquals(2, HfResultsView.frequentTags(tagged, limit = 2).size)
    }

    @Test
    fun applyWithLanguageFilterKeepsThatLanguagePlusMultilingual() {
        val langResults =
            listOf(
                HfModelSummary(id = "en-only", downloads = 30, tags = listOf("tts", "en")),
                HfModelSummary(id = "es-only", downloads = 20, tags = listOf("tts", "es")),
                HfModelSummary(id = "multi", downloads = 10, tags = listOf("tts", "multilingual")),
            )
        val filtered =
            HfResultsView.apply(
                langResults,
                HfSortOption.MOST_DOWNLOADS,
                tag = null,
                filters = HfResultFilters(language = "en"),
            )
        assertEquals(listOf("en-only", "multi"), filtered.map { it.id })
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
        val filtered =
            HfResultsView.apply(
                results,
                HfSortOption.LARGEST_SIZE,
                tag = null,
                filters = HfResultFilters(sizeEstimates = sizeEstimates, sizeFilter = filter),
            )
        assertEquals(listOf("hexgrad/Kokoro-82M", "rhasspy/piper-voices"), filtered.map { it.id })
    }

    private val rtfEstimates =
        mapOf(
            // Kokoro measured fast.
            "hexgrad/Kokoro-82M" to 0.3,
            // Piper measured slower.
            "rhasspy/piper-voices" to 0.9,
            // coqui/XTTS-v2 intentionally absent: never benchmarked.
        )

    @Test
    fun sortsByFastestRtfWithUnknownRtfLast() {
        val sorted = HfResultsView.sort(results, HfSortOption.FASTEST_RTF, rtfEstimates = rtfEstimates)
        assertEquals(listOf("hexgrad/Kokoro-82M", "rhasspy/piper-voices", "coqui/XTTS-v2"), sorted.map { it.id })
    }

    @Test
    fun sortsBySlowestRtfWithUnknownRtfLast() {
        val sorted = HfResultsView.sort(results, HfSortOption.SLOWEST_RTF, rtfEstimates = rtfEstimates)
        assertEquals(listOf("rhasspy/piper-voices", "hexgrad/Kokoro-82M", "coqui/XTTS-v2"), sorted.map { it.id })
    }

    @Test
    fun rtfDependentSortOptionsAreFlaggedAsNeedingRtf() {
        assertTrue(HfSortOption.FASTEST_RTF.needsRtf())
        assertTrue(HfSortOption.SLOWEST_RTF.needsRtf())
        assertFalse(HfSortOption.MOST_DOWNLOADS.needsRtf())
        assertFalse(HfSortOption.LARGEST_SIZE.needsRtf())
    }

    @Test
    fun applyWithRtfSortComposesRtfEstimatesIntoSort() {
        val sorted =
            HfResultsView.apply(
                results,
                HfSortOption.FASTEST_RTF,
                tag = null,
                filters = HfResultFilters(rtfEstimates = rtfEstimates),
            )
        assertEquals(listOf("hexgrad/Kokoro-82M", "rhasspy/piper-voices", "coqui/XTTS-v2"), sorted.map { it.id })
    }

    @Test
    fun filterByInstalledKeepsOnlyInstalledResults() {
        val filtered =
            HfResultsView.filterByInstalled(results, setOf("hexgrad/Kokoro-82M"), HfInstalledFilter.INSTALLED_ONLY)
        assertEquals(listOf("hexgrad/Kokoro-82M"), filtered.map { it.id })
    }

    @Test
    fun filterByInstalledKeepsOnlyNotInstalledResults() {
        val filtered =
            HfResultsView.filterByInstalled(
                results,
                setOf("hexgrad/Kokoro-82M"),
                HfInstalledFilter.NOT_INSTALLED_ONLY,
            )
        assertEquals(listOf("rhasspy/piper-voices", "coqui/XTTS-v2"), filtered.map { it.id })
    }

    @Test
    fun filterByInstalledAllIsANoOp() {
        val filtered = HfResultsView.filterByInstalled(results, emptySet(), HfInstalledFilter.ALL)
        assertEquals(results.map { it.id }, filtered.map { it.id })
    }

    @Test
    fun needsEagerSizeFetchIsTrueWhenTheSortNeedsSize() {
        assertTrue(needsEagerSizeFetch(HfSortOption.LARGEST_SIZE, HfSizeParamFilter()))
    }

    @Test
    fun needsEagerSizeFetchIsTrueWhenAnActiveSizeFilterIsSetRegardlessOfSort() {
        // Regression: a max-size filter with a non-size sort must still trigger the eager fetch,
        // otherwise unfetched-size results are excluded by filterBySize and never get a chance to
        // resolve - see needsEagerSizeFetch's kdoc.
        assertTrue(needsEagerSizeFetch(HfSortOption.MOST_DOWNLOADS, HfSizeParamFilter(maxBytes = 1_000L)))
    }

    @Test
    fun needsEagerSizeFetchIsFalseWithNeitherASizeSortNorAnActiveSizeFilter() {
        assertFalse(needsEagerSizeFetch(HfSortOption.MOST_DOWNLOADS, HfSizeParamFilter()))
    }

    // --- Browse crash investigation (owner report: "may be related to filters/sort") ---
    //
    // Prime suspect from the lead: java.util.TimSort throws "Comparison method violates its
    // general contract!" for an inconsistent comparator, but ONLY once a list exceeds 32 elements
    // (its insertion-sort fallback threshold) - exactly why a 3-item unit test would never catch
    // this class of bug, and why it would only show up on a real, large Hugging Face result page.
    // A Double sort key that can be NaN is the classic trigger IF the comparator uses raw `<`/`>`
    // (inconsistent for NaN - every relational comparison with NaN is false). These tests build a
    // 150-element result set - realistic HF page sizes - with NaN, duplicate, and merely-large RTF
    // values mixed in against every sort option HfResultsView exposes, to give a definitive,
    // reproduced verdict rather than a guess.
    private fun manyResults(count: Int): List<HfModelSummary> =
        (0 until count).map { i ->
            HfModelSummary(id = "org$i/model-$i", likes = i * 7 % 500, downloads = (count - i) * 13 % 1000, tags = listOf("tts"))
        }

    @Test
    fun sortingOverThirtyTwoResultsByEveryPlainOptionNeverThrows() {
        val big = manyResults(150)
        // MOST_DOWNLOADS/MOST_LIKES/NAME_ASC read plain Int/String fields off HfModelSummary itself
        // - never NaN-capable - so these are expected to be safe; asserted here at realistic scale
        // per the investigation's "not a 3-item toy" requirement rather than assumed.
        HfSortOption.entries.forEach { option ->
            val sorted = HfResultsView.sort(big, option)
            assertEquals(big.size, sorted.size)
        }
    }

    @Test
    fun sortingByRtfWithNaNAndInfinityMixedIntoA150ElementListDoesNotThrow() {
        val big = manyResults(150)
        // A NaN or Infinity RTF should never be constructible through BenchmarkRecord/RtfResult's
        // own require() guards in normal operation (see BenchmarkHistoryTest's NaN-decode
        // regression for the one place a NaN COULD reach this map: a corrupt/legacy persisted
        // value) - but HfResultsView.sort itself takes a bare Map<String, Double> with no
        // validation of its own, so this proves its behavior at the boundary regardless of how a
        // bad value might arrive.
        val rtf =
            big.indices.associate { i ->
                val value =
                    when {
                        i % 37 == 0 -> Double.NaN
                        i % 11 == 0 -> Double.POSITIVE_INFINITY
                        i % 5 == 0 -> null // stays "unknown" - never fetched/benchmarked
                        else -> i.toDouble() * 0.01
                    }
                big[i].id to value
            }.filterValues { it != null }.mapValues { it.value as Double }

        // The real question this answers: does Kotlin's sortedBy (java.util.Comparable.compareTo,
        // NOT the raw < / > operators) throw TimSort's contract-violation exception when NaN is
        // mixed into a >32-element Double sort? It must not, and both directions are exercised.
        val fastest = HfResultsView.sort(big, HfSortOption.FASTEST_RTF, rtfEstimates = rtf)
        val slowest = HfResultsView.sort(big, HfSortOption.SLOWEST_RTF, rtfEstimates = rtf)
        assertEquals(big.size, fastest.size)
        assertEquals(big.size, slowest.size)
        // Every known (non-null) RTF entry still sorts before every unknown one, in EITHER
        // direction - NaN behaves as Double.compareTo's "greater than everything else" rather than
        // corrupting the ordering guarantee.
        val unknownIds = big.map { it.id }.toSet() - rtf.keys
        assertTrue(fastest.takeLast(unknownIds.size).map { it.id }.toSet() == unknownIds)
        assertTrue(slowest.takeLast(unknownIds.size).map { it.id }.toSet() == unknownIds)
    }

    @Test
    fun sortingBySizeAndParamsOverA150ElementListWithManyKnownAndUnknownSizesDoesNotThrow() {
        val big = manyResults(150)
        val sizes =
            big.indices.filter { it % 3 != 0 }.associate { i ->
                big[i].id to HfSizeEstimate(knownBytes = (i.toLong() + 1) * 1_000_000L, unknownFileCount = 0)
            }
        val sizeAndParamOptions =
            listOf(
                HfSortOption.LARGEST_SIZE,
                HfSortOption.SMALLEST_SIZE,
                HfSortOption.MOST_PARAMS,
                HfSortOption.FEWEST_PARAMS,
            )
        sizeAndParamOptions.forEach { option ->
            val sorted = HfResultsView.sort(big, option, sizeEstimates = sizes)
            assertEquals(big.size, sorted.size)
        }
    }

    @Test
    fun applyOverA150ElementListWithEveryAdvancedFilterAndAllSortOptionsNeverThrows() {
        // End-to-end version of the above three: the exact call HfBrowseViewModel.applyFilters
        // makes on every recompute, across every sort option, at realistic scale.
        val big = manyResults(150)
        val sizes =
            big.indices.filter { it % 2 == 0 }.associate { i ->
                big[i].id to HfSizeEstimate(knownBytes = (i.toLong() + 1) * 500_000L, unknownFileCount = 0)
            }
        val rtf =
            big.indices.filter { it % 4 == 0 }.associate { i ->
                big[i].id to if (i % 40 == 0) Double.NaN else i.toDouble() * 0.02
            }
        HfSortOption.entries.forEach { option ->
            val filtered =
                HfResultsView.apply(
                    big,
                    option,
                    tag = "tts",
                    filters = HfResultFilters(sizeEstimates = sizes, rtfEstimates = rtf),
                )
            assertTrue(filtered.size <= big.size)
        }
    }
}
