package com.phonetts.core.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableErrorLogTest {
    @Test
    fun startsEmpty() {
        assertTrue(DurableErrorLog(InMemoryDurableStore()).entries().isEmpty())
    }

    @Test
    fun recordsNewestFirstAndKeepsSourceAndMessage() {
        val log = DurableErrorLog(InMemoryDurableStore())
        log.record(atMs = 1L, source = "browse", message = "search failed")
        log.record(atMs = 2L, source = "browse", message = "size lookup failed")

        val entries = log.entries()
        assertEquals(2, entries.size)
        assertEquals("size lookup failed", entries.first().message, "most recent first")
        assertEquals("browse", entries.first().source)
    }

    @Test
    fun survivesReloadFromAFreshInstance() {
        val backing = InMemoryDurableStore()
        DurableErrorLog(backing).record(atMs = 5L, source = "download", message = "connection abort")

        val reloaded = DurableErrorLog(backing).entries()
        assertEquals(1, reloaded.size)
        assertEquals("connection abort", reloaded.single().message)
    }

    @Test
    fun boundsEntryCount() {
        val log = DurableErrorLog(InMemoryDurableStore(), maxEntries = 5)
        repeat(20) { i -> log.record(atMs = i.toLong(), source = "s", message = "m$i") }

        assertEquals(5, log.entries().size)
        assertEquals("m19", log.entries().first().message)
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = DurableErrorLog(InMemoryDurableStore())
        log.record(atMs = 1L, source = "s", message = "x")

        log.clear()

        assertTrue(log.entries().isEmpty())
    }
}
