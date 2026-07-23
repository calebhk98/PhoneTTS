package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HfBrowseFiltersTest {
    private val results =
        listOf(
            HfModelSummary(id = "runnable", tags = listOf("onnx")),
            HfModelSummary(id = "convertible", tags = listOf("safetensors")),
            HfModelSummary(id = "apple", tags = listOf("mlx")),
            HfModelSummary(id = "unfetched"),
        )
    private val compatibility =
        mapOf(
            "runnable" to RunCompatibility.RUNNABLE,
            "convertible" to RunCompatibility.NEEDS_CONVERSION,
            "apple" to RunCompatibility.IMPOSSIBLE,
        )

    @Test
    fun supportedFilterKeepsOnlyRunnableAndDropsUnfetched() {
        val filtered = HfSupportedFilters.filter(results, compatibility, HfSupportedFilter.RUNNABLE)
        assertEquals(listOf("runnable"), filtered.map { it.id })
    }

    @Test
    fun supportedFilterCanIsolateImpossibleAndNeedsConversion() {
        val impossible = HfSupportedFilters.filter(results, compatibility, HfSupportedFilter.IMPOSSIBLE)
        assertEquals(listOf("apple"), impossible.map { it.id })
        val convertible = HfSupportedFilters.filter(results, compatibility, HfSupportedFilter.NEEDS_CONVERSION)
        assertEquals(listOf("convertible"), convertible.map { it.id })
    }

    @Test
    fun supportedFilterAllIsANoOp() {
        val all = HfSupportedFilters.filter(results, compatibility, HfSupportedFilter.ALL)
        assertEquals(results.map { it.id }, all.map { it.id })
    }

    // A ~30M-param model estimates near 8x real-time; a huge one estimates well under 1x. int8 tags
    // halve the byte-per-param, so the same bytes imply more params (slower) - proves the estimate
    // reads the precision hint, not just the size.
    private val speedResults =
        listOf(
            HfModelSummary(id = "tiny"),
            HfModelSummary(id = "huge"),
            HfModelSummary(id = "unfetched"),
        )
    private val sizeEstimates =
        mapOf(
            "tiny" to HfSizeEstimate(knownBytes = 60_000_000L, unknownFileCount = 0),
            "huge" to HfSizeEstimate(knownBytes = 4_000_000_000L, unknownFileCount = 0),
        )

    @Test
    fun rtfSliderKeepsOnlyFastEnoughEstimatesAndDropsUnfetched() {
        val filtered = HfSpeedFilter.filterByMinRealtime(speedResults, sizeEstimates, minMultiple = 1.0)
        assertEquals(listOf("tiny"), filtered.map { it.id })
    }

    @Test
    fun rtfSliderWithNullIsANoOp() {
        val filtered = HfSpeedFilter.filterByMinRealtime(speedResults, sizeEstimates, minMultiple = null)
        assertEquals(speedResults.map { it.id }, filtered.map { it.id })
    }

    @Test
    fun needsFileDataIsTrueForSupportedFormatOrRtfButNotEngine() {
        assertTrue(HfBrowseFilters.needsFileData(HfResultFilters(supportedFilter = HfSupportedFilter.RUNNABLE)))
        assertTrue(HfBrowseFilters.needsFileData(HfResultFilters(formatFilter = HfFileFormat.ONNX)))
        assertTrue(HfBrowseFilters.needsFileData(HfResultFilters(minRealtimeMultiple = 2.0)))
        assertFalse(HfBrowseFilters.needsFileData(HfResultFilters(engineFilter = "kokoro")))
        assertFalse(HfBrowseFilters.needsFileData(HfResultFilters()))
    }

    @Test
    fun applyComposesAdvancedFiltersThroughHfResultsView() {
        val filters =
            HfResultFilters(
                compatibility = compatibility,
                supportedFilter = HfSupportedFilter.RUNNABLE,
                formats = mapOf("runnable" to setOf(HfFileFormat.ONNX)),
                formatFilter = HfFileFormat.ONNX,
            )
        val filtered = HfResultsView.apply(results, HfSortOption.MOST_DOWNLOADS, tag = null, filters = filters)
        assertEquals(listOf("runnable"), filtered.map { it.id })
    }
}
