package com.phonetts.core.metrics

import com.phonetts.core.store.DurableStore
import com.phonetts.core.store.InMemoryDurableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsageStatsStoreTest {
    @Test
    fun startsAtAllZeroSnapshot() {
        val snapshot = UsageStatsStore(InMemoryDurableStore()).snapshot()

        assertEquals(UsageStatsSnapshot.EMPTY, snapshot)
    }

    @Test
    fun recordGenerationAccumulatesCountAndWords() {
        val store = UsageStatsStore(InMemoryDurableStore())

        store.recordGeneration(wordCount = 10)
        store.recordGeneration(wordCount = 5)

        val snapshot = store.snapshot()
        assertEquals(2L, snapshot.generationCount)
        assertEquals(15L, snapshot.totalWordsSpoken)
    }

    @Test
    fun recordPlaybackAccumulatesListeningTimeAndPerModelCounts() {
        val store = UsageStatsStore(InMemoryDurableStore())

        store.recordPlayback("piper", listenedMillis = 4_000L)
        store.recordPlayback("piper", listenedMillis = 6_000L)
        store.recordPlayback("kokoro", listenedMillis = 1_000L)

        val snapshot = store.snapshot()
        assertEquals(11_000L, snapshot.totalListeningMillis)
        assertEquals(2L, snapshot.playCountByModelId["piper"])
        assertEquals(1L, snapshot.playCountByModelId["kokoro"])
        assertEquals(
            listOf("piper" to 2L, "kokoro" to 1L),
            snapshot.playCountsDescending,
        )
    }

    @Test
    fun accumulatesAcrossRelaunchesOverTheSameBackingDocument() {
        val backing = InMemoryDurableStore()
        UsageStatsStore(backing).apply {
            recordGeneration(wordCount = 20)
            recordPlayback("piper", listenedMillis = 5_000L)
        }

        // A fresh instance over the SAME backing document is what process death + relaunch looks
        // like (issue #130): construction must pick up exactly what the previous instance left.
        val reloaded = UsageStatsStore(backing)
        reloaded.recordGeneration(wordCount = 5)
        reloaded.recordPlayback("piper", listenedMillis = 1_000L)

        val snapshot = reloaded.snapshot()
        assertEquals(2L, snapshot.generationCount)
        assertEquals(25L, snapshot.totalWordsSpoken)
        assertEquals(6_000L, snapshot.totalListeningMillis)
        assertEquals(2L, snapshot.playCountByModelId["piper"])
    }

    @Test
    fun perModelCountsAreKeyedIndependently() {
        val store = UsageStatsStore(InMemoryDurableStore())

        store.recordPlayback("piper", listenedMillis = 1_000L)
        store.recordPlayback("kokoro", listenedMillis = 1_000L)
        store.recordPlayback("piper", listenedMillis = 1_000L)
        store.recordPlayback("melotts", listenedMillis = 1_000L)

        val counts = store.snapshot().playCountByModelId
        assertEquals(3, counts.size)
        assertEquals(2L, counts["piper"])
        assertEquals(1L, counts["kokoro"])
        assertEquals(1L, counts["melotts"])
    }

    @Test
    fun corruptDocumentLoadsAsEmptyRatherThanThrowing() {
        val backing = InMemoryDurableStore()
        backing.write(UsageStatsStore.DOCUMENT_NAME, "not json at all {{{")

        val store = UsageStatsStore(backing)

        assertEquals(UsageStatsSnapshot.EMPTY, store.snapshot())
    }

    @Test
    fun missingDocumentLoadsAsEmpty() {
        val store = UsageStatsStore(InMemoryDurableStore())

        assertEquals(UsageStatsSnapshot.EMPTY, store.snapshot())
    }

    @Test
    fun nothingThrowsWhenTheBackingStoreWriteFails() {
        val throwingStore =
            object : DurableStore {
                private val delegate = InMemoryDurableStore()

                override fun read(name: String): String? = delegate.read(name)

                override fun write(
                    name: String,
                    contents: String,
                ) {
                    error("simulated disk-full write failure")
                }

                override fun delete(name: String) = delegate.delete(name)
            }
        val store = UsageStatsStore(throwingStore)

        // Must not propagate the write failure - persistence is best-effort, matching every other
        // DurableStore-backed seam (FavoritesStore, DurableErrorLog).
        store.recordGeneration(wordCount = 3)
        store.recordPlayback("piper", listenedMillis = 2_000L)

        // The in-memory total for the REST of this process still advances even though the write
        // never landed - only the next relaunch would fail to see it.
        val snapshot = store.snapshot()
        assertEquals(1L, snapshot.generationCount)
        assertEquals(3L, snapshot.totalWordsSpoken)
        assertEquals(2_000L, snapshot.totalListeningMillis)
    }

    @Test
    fun rejectsNegativeInputs() {
        val store = UsageStatsStore(InMemoryDurableStore())

        assertTrue(runCatching { store.recordGeneration(wordCount = -1) }.isFailure)
        assertTrue(runCatching { store.recordPlayback("piper", listenedMillis = -1L) }.isFailure)
        assertTrue(runCatching { store.recordPlayback("", listenedMillis = 100L) }.isFailure)
    }
}
