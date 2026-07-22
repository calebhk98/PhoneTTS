package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HfSizeEstimateTest {
    @Test
    fun sumsKnownFileSizesAndIgnoresDirectories() {
        val files =
            listOf(
                HfTreeEntry(type = "directory", path = "onnx"),
                HfTreeEntry(type = "file", path = "config.json", size = 1_200),
                HfTreeEntry(type = "file", path = "onnx/model.onnx", size = 8_400_000),
            )

        val estimate = HfSizeEstimator.estimate(files)

        assertEquals(8_401_200L, estimate.knownBytes)
        assertEquals(0, estimate.unknownFileCount)
        assertTrue(estimate.isExact)
    }

    @Test
    fun aFileWithNoSizeIsCountedAsUnknownRatherThanZero() {
        val files =
            listOf(
                HfTreeEntry(type = "file", path = "config.json", size = 1_200),
                HfTreeEntry(type = "file", path = "weights.bin", size = null),
            )

        val estimate = HfSizeEstimator.estimate(files)

        assertEquals(1_200L, estimate.knownBytes, "the unknown file must not silently count as 0 bytes")
        assertEquals(1, estimate.unknownFileCount)
        assertFalse(estimate.isExact)
    }

    @Test
    fun estimatesFromAResolvedDownloadPlanToo() {
        val items =
            listOf(
                HfDownloadItem(url = "https://x/config.json", relativePath = "config.json", sizeBytes = 1_200),
                HfDownloadItem(url = "https://x/model.onnx", relativePath = "model.onnx", sizeBytes = 8_400_000),
            )

        val estimate = HfSizeEstimator.estimateItems(items)

        assertEquals(8_401_200L, estimate.knownBytes)
        assertTrue(estimate.isExact)
    }
}
