package com.phonetts.core.registry

import com.phonetts.core.testing.testDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelCatalogTest {
    @Test
    fun addedModelAppearsInTheList() {
        val catalog = ModelCatalog()
        catalog.add(testDescriptor("m1", "eng"))
        assertEquals(listOf("m1"), catalog.list().map { it.modelId })
    }

    @Test
    fun addingSameModelIdReplacesTheDescriptor() {
        val catalog = ModelCatalog()
        catalog.add(testDescriptor("m1", "eng", sampleRate = 22_050))
        catalog.add(testDescriptor("m1", "eng", sampleRate = 24_000))
        assertEquals(1, catalog.list().size)
        assertEquals(24_000, catalog.get("m1")?.sampleRate)
    }

    @Test
    fun removedModelVanishes() {
        val catalog = ModelCatalog()
        catalog.add(testDescriptor("m1", "eng"))
        catalog.remove("m1")
        assertNull(catalog.get("m1"))
        assertEquals(emptyList(), catalog.list())
    }

    @Test
    fun markUnresolvedRecordsAnUnclaimedBundle() {
        val catalog = ModelCatalog()
        catalog.markUnresolved("mystery", "no engine claimed it")
        assertEquals(listOf(UnresolvedModel("mystery", "no engine claimed it")), catalog.listUnresolved())
        // It stays out of the real model list — it isn't selectable, only visible (issue #8).
        assertEquals(emptyList(), catalog.list())
    }

    @Test
    fun clearingUnresolvedDropsItsMarker() {
        val catalog = ModelCatalog()
        catalog.markUnresolved("mystery", "no engine claimed it")
        catalog.clearUnresolved("mystery")
        assertEquals(emptyList(), catalog.listUnresolved())
    }

    @Test
    fun addingAResolvedDescriptorSupersedesAStaleUnresolvedMarker() {
        val catalog = ModelCatalog()
        catalog.markUnresolved("m1", "no engine claimed it")
        catalog.add(testDescriptor("m1", "eng"))
        assertEquals(emptyList(), catalog.listUnresolved())
        assertEquals(listOf("m1"), catalog.list().map { it.modelId })
    }

    @Test
    fun markingUnresolvedNeverOverridesAnAlreadyIdentifiedModel() {
        val catalog = ModelCatalog()
        catalog.add(testDescriptor("m1", "eng"))
        catalog.markUnresolved("m1", "stale retry")
        assertEquals(emptyList(), catalog.listUnresolved())
        assertEquals("eng", catalog.get("m1")?.engineId)
    }

    @Test
    fun clearDropsBothIdentifiedAndUnresolvedEntries() {
        val catalog = ModelCatalog()
        catalog.add(testDescriptor("m1", "eng"))
        catalog.markUnresolved("mystery", "no engine claimed it")

        catalog.clear()

        assertEquals(emptyList(), catalog.list())
        assertEquals(emptyList(), catalog.listUnresolved())
    }
}
