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
}
