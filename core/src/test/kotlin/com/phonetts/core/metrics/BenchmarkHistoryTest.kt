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

    @Test
    fun `reads fail closed on a stored NaN RTF, skipping it rather than throwing`() {
        // Regression (Browse crash investigation): "NaN".toDoubleOrNull() successfully parses to
        // Double.NaN, and NaN < 0.0 is always false (NaN fails every relational comparison), so the
        // decode() guard `rtf < 0.0 return null` silently lets a NaN value through instead of
        // rejecting it as corrupt. It then reaches BenchmarkRecord's init block, whose
        // `require(realTimeFactor >= 0.0)` also evaluates false for NaN and THROWS
        // IllegalArgumentException - out of decode(), out of the mapNotNull in history(), uncaught -
        // breaking this class's own documented contract ("malformed lines are skipped rather than
        // crashing the parse"). Browse's RTF sort (HfBrowseViewModel.rtfEstimates) calls
        // BenchmarkHistory.history() on every recompute, so one corrupt/legacy NaN entry here would
        // take the whole Browse screen down before this fix.
        val backing = InMemoryPreferenceStore()
        backing.putString("benchmark_history.piper|Pixel", "100|NaN\n200|0.8")
        val history = BenchmarkHistory(backing)
        val recorded = history.history("piper", "Pixel")
        assertEquals(1, recorded.size)
        assertEquals(200L, recorded.first().timestampMillis)
    }

    // --- allKeys()/replaceHistory() (issue #149: backup export/restore need to enumerate every
    // engine+device pair and write a whole saved history back at once) ------------------------------

    @Test
    fun `allKeys is empty until something is recorded`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        assertTrue(history.allKeys().isEmpty())
    }

    @Test
    fun `allKeys lists every distinct engine-device pair exactly once`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        history.record(BenchmarkRecord("piper", "Pixel", 1L, 0.5))
        history.record(BenchmarkRecord("piper", "Pixel", 2L, 0.6)) // same pair again - no duplicate key
        history.record(BenchmarkRecord("kokoro", "Pixel", 1L, 1.0))

        assertEquals(setOf("piper" to "Pixel", "kokoro" to "Pixel"), history.allKeys().toSet())
    }

    @Test
    fun `clear removes the pair from allKeys too`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        history.record(BenchmarkRecord("piper", "Pixel", 1L, 0.5))
        history.clear("piper", "Pixel")
        assertTrue(history.allKeys().isEmpty())
    }

    @Test
    fun `replaceHistory overwrites the whole saved series and updates the index`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        history.record(BenchmarkRecord("piper", "Pixel", 1L, 0.5))

        history.replaceHistory(
            "piper",
            "Pixel",
            listOf(BenchmarkRecord("piper", "Pixel", 10L, 0.1), BenchmarkRecord("piper", "Pixel", 20L, 0.2)),
        )

        assertEquals(listOf(10L, 20L), history.history("piper", "Pixel").map { it.timestampMillis })
        assertEquals(listOf("piper" to "Pixel"), history.allKeys())
    }

    @Test
    fun `replaceHistory with an empty list clears the series like clear() would`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        history.record(BenchmarkRecord("piper", "Pixel", 1L, 0.5))

        history.replaceHistory("piper", "Pixel", emptyList())

        assertTrue(history.history("piper", "Pixel").isEmpty())
        assertTrue(history.allKeys().isEmpty())
    }

    @Test
    fun `replaceHistory ignores records for a different engine or device`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        history.replaceHistory(
            "piper",
            "Pixel",
            listOf(BenchmarkRecord("piper", "Pixel", 1L, 0.5), BenchmarkRecord("kokoro", "Pixel", 1L, 1.0)),
        )

        assertEquals(1, history.history("piper", "Pixel").size)
        assertTrue(history.history("kokoro", "Pixel").isEmpty())
    }

    @Test
    fun `replaceHistory also enforces the fifty-sample cap`() {
        val history = BenchmarkHistory(InMemoryPreferenceStore())
        val many = (1..60).map { BenchmarkRecord("piper", "Pixel", it.toLong(), 1.0) }

        history.replaceHistory("piper", "Pixel", many)

        val kept = history.history("piper", "Pixel")
        assertEquals(50, kept.size)
        assertEquals(11L, kept.first().timestampMillis)
        assertEquals(60L, kept.last().timestampMillis)
    }
}
