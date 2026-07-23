package com.phonetts.core.store

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class Sample(
    val id: Int,
    val label: String,
)

class JsonListStoreTest {
    private fun newStore(
        backing: DurableStore,
        max: Int = 100,
    ) = JsonListStore(backing, "sample", Sample.serializer(), max)

    @Test
    fun startsEmptyWhenNothingStored() {
        assertTrue(newStore(InMemoryDurableStore()).all().isEmpty())
    }

    @Test
    fun recordsNewestFirst() {
        val store = newStore(InMemoryDurableStore())
        store.record(Sample(1, "first"))
        store.record(Sample(2, "second"))

        val all = store.all()
        assertEquals(2, all.size)
        assertEquals(2, all.first().id, "most recent entry should be first")
        assertEquals(1, all.last().id)
    }

    @Test
    fun survivesReloadFromAFreshInstanceOverTheSameBacking() {
        val backing = InMemoryDurableStore()
        newStore(backing).record(Sample(7, "kept"))

        val reloaded = newStore(backing).all()
        assertEquals(1, reloaded.size)
        assertEquals("kept", reloaded.single().label)
    }

    @Test
    fun boundsSoTheListCannotGrowUnbounded() {
        val store = newStore(InMemoryDurableStore(), max = 3)
        repeat(10) { i -> store.record(Sample(i, "s$i")) }

        val all = store.all()
        assertEquals(3, all.size)
        assertEquals(9, all.first().id, "newest kept")
        assertEquals(7, all.last().id, "oldest beyond the bound dropped")
    }

    @Test
    fun replaceAllKeepsOrderAndTrimsToBound() {
        val store = newStore(InMemoryDurableStore(), max = 2)
        store.replaceAll(listOf(Sample(1, "a"), Sample(2, "b"), Sample(3, "c")))

        assertEquals(listOf(1, 2), store.all().map { it.id })
    }

    @Test
    fun clearRemovesEverything() {
        val store = newStore(InMemoryDurableStore())
        store.record(Sample(1, "x"))

        store.clear()

        assertTrue(store.all().isEmpty())
    }

    @Test
    fun corruptDocumentReadsBackAsEmptyRatherThanThrowing() {
        val backing = InMemoryDurableStore()
        backing.write("sample", "not valid json {{{")

        assertTrue(newStore(backing).all().isEmpty())
    }
}
