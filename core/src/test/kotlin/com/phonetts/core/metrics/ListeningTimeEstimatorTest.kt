package com.phonetts.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListeningTimeEstimatorTest {
    @Test
    fun `empty text estimates zero`() {
        assertEquals(0.0, ListeningTimeEstimator.estimateSeconds(0, 1.0f), 0.0)
    }

    @Test
    fun `at normal speed the estimate is words divided by the base rate`() {
        // One minute of words at the base rate should estimate ~60 seconds at 1.0x.
        val oneMinuteOfWords = ListeningTimeEstimator.WORDS_PER_MINUTE.toInt()
        assertEquals(60.0, ListeningTimeEstimator.estimateSeconds(oneMinuteOfWords, 1.0f), 0.001)
    }

    @Test
    fun `faster speed proportionally shortens the estimate`() {
        val words = 300
        val atNormal = ListeningTimeEstimator.estimateSeconds(words, 1.0f)
        val atDouble = ListeningTimeEstimator.estimateSeconds(words, 2.0f)
        assertEquals(atNormal / 2.0, atDouble, 0.001)
    }

    @Test
    fun `slower speed lengthens the estimate`() {
        val words = 300
        val atNormal = ListeningTimeEstimator.estimateSeconds(words, 1.0f)
        val atHalf = ListeningTimeEstimator.estimateSeconds(words, 0.5f)
        assertTrue(atHalf > atNormal)
        assertEquals(atNormal * 2.0, atHalf, 0.001)
    }

    @Test
    fun `non-positive speed estimates zero rather than dividing by zero`() {
        assertEquals(0.0, ListeningTimeEstimator.estimateSeconds(100, 0.0f), 0.0)
        assertEquals(0.0, ListeningTimeEstimator.estimateSeconds(100, -1.0f), 0.0)
    }
}
