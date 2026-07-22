package com.phonetts.engines.executorch

import com.phonetts.core.runtime.Tensor
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DurationExpansionTest {
    @Test
    fun indicesRepeatsEachTokenIndexItsOwnDurationTimesInOrder() {
        val result = DurationExpansion.indices(intArrayOf(1, 2, 3, 1), maxDuration = 100)

        assertContentEquals(longArrayOf(0, 1, 1, 2, 2, 2, 3), result)
    }

    @Test
    fun indicesTruncatesAtMaxDuration() {
        val result = DurationExpansion.indices(intArrayOf(5, 5, 5), maxDuration = 4)

        assertContentEquals(longArrayOf(0, 0, 0, 0), result)
    }

    @Test
    fun indicesTreatsANonPositiveDurationAsZeroRepeats() {
        val result = DurationExpansion.indices(intArrayOf(1, 0, -3, 2), maxDuration = 100)

        assertContentEquals(longArrayOf(0, 3, 3), result)
    }

    @Test
    fun indicesOnAllZeroDurationsIsEmpty() {
        val result = DurationExpansion.indices(intArrayOf(0, 0, 0), maxDuration = 100)

        assertContentEquals(LongArray(0), result)
    }

    @Test
    fun truncateMiddleDimKeepsOnlyTheFirstLengthRows() {
        // A [1, 3, 2] tensor flattened row-major: row r's two values are (10r, 10r+1).
        val flat = floatArrayOf(0f, 1f, 10f, 11f, 20f, 21f)

        val result = DurationExpansion.truncateMiddleDim(flat, dim1 = 3, dim2 = 2, length = 2)

        assertContentEquals(floatArrayOf(0f, 1f, 10f, 11f), result)
    }

    @Test
    fun truncateMiddleDimClampsLengthToDim1RatherThanReadingPastTheBuffer() {
        val flat = floatArrayOf(0f, 1f, 10f, 11f)

        val result = DurationExpansion.truncateMiddleDim(flat, dim1 = 2, dim2 = 2, length = 50)

        assertContentEquals(floatArrayOf(0f, 1f, 10f, 11f), result)
    }

    @Test
    fun toIntCountsReadsALongTensor() {
        val tensor = Tensor.longs(longArrayOf(1, 2, 3))

        assertContentEquals(intArrayOf(1, 2, 3), tensor.toIntCounts())
    }

    @Test
    fun toIntCountsFallsBackToAFloatTensor() {
        val tensor = Tensor.floats(floatArrayOf(1f, 2f, 3f))

        assertContentEquals(intArrayOf(1, 2, 3), tensor.toIntCounts())
    }
}
