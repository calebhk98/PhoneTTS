package com.phonetts.core.metrics

import com.phonetts.core.prefs.InMemoryPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkHistoryTest {
    @Test
    fun `history is empty for an engine that was never benchmarked`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        assertTrue(history.history("piper", "Pixel").isEmpty())
    }

    @Test
    fun `record then read round-trips samples oldest first`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        history.record(BenchmarkRecord("piper", "Pixel", timestampMillis = 200L, realTimeFactor = 0.9))
        history.record(BenchmarkRecord("piper", "Pixel", timestampMillis = 100L, realTimeFactor = 0.8))

        val recorded = history.history("piper", "Pixel")
        assertEquals(listOf(100L, 200L), recorded.map { it.timestampMillis })
        assertEquals(0.8, recorded.first().realTimeFactor)
    }

    @Test
    fun `different engine or device do not collide`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        history.record(BenchmarkRecord("piper", "Pixel", 1L, 0.5))
        history.record(BenchmarkRecord("kokoro", "Pixel", 1L, 1.5))
        history.record(BenchmarkRecord("piper", "A16", 1L, 3.0))

        assertEquals(1, history.history("piper", "Pixel").size)
        assertEquals(1, history.history("kokoro", "Pixel").size)
        assertEquals(1, history.history("piper", "A16").size)
    }

    @Test
    fun `clear forgets a saved series`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        history.record(BenchmarkRecord("piper", "Pixel", 1L, 0.9))
        history.clear("piper", "Pixel")
        assertTrue(history.history("piper", "Pixel").isEmpty())
    }

    @Test
    fun `history caps at fifty most-recent samples`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        for (i in 1..60) {
            history.record(BenchmarkRecord("piper", "Pixel", timestampMillis = i.toLong(), realTimeFactor = 1.0))
        }
        val kept = history.history("piper", "Pixel")
        assertEquals(50, kept.size)
        assertEquals(11L, kept.first().timestampMillis)
        assertEquals(60L, kept.last().timestampMillis)
    }

    @Test
    fun `reads fail closed on a corrupt line, skipping it`() {
        val backing = InMemoryPreferenceStore()
        backing.putString("benchmark_history.piper|Pixel", "garbage\n100|0.8")
        val history = BenchmarkHistory(backing)
        val recorded = history.history("piper", "Pixel")
        assertEquals(1, recorded.size)
        assertEquals(100L, recorded.first().timestampMillis)
    }
}
