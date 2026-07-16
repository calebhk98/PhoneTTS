package com.phonetts.core.registry

import com.phonetts.core.resolver.OverrideStore
import com.phonetts.core.testing.FakeEngine
import com.phonetts.core.testing.testDescriptor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [ClearableOverrideStore] double so tests can assert a saved override was actually dropped. */
private class FakeClearableOverrideStore : ClearableOverrideStore {
    private val overrides = mutableMapOf<String, String>()

    override fun get(bundleId: String): String? = overrides[bundleId]

    override fun put(
        bundleId: String,
        engineId: String,
    ) {
        overrides[bundleId] = engineId
    }

    override fun clear(bundleId: String) {
        overrides.remove(bundleId)
    }
}

/** A plain, non-clearable [OverrideStore] double — exercises the "not ClearableOverrideStore" path. */
private class PlainOverrideStore : OverrideStore {
    private val overrides = mutableMapOf<String, String>()

    override fun get(bundleId: String): String? = overrides[bundleId]

    override fun put(
        bundleId: String,
        engineId: String,
    ) {
        overrides[bundleId] = engineId
    }
}

class ModelManagerTest {
    @Test
    fun usageReportsTheInjectedSizeForEveryCatalogedModel() {
        val catalog =
            ModelCatalog().apply {
                add(testDescriptor("m1", "eng"))
                add(testDescriptor("m2", "eng"))
            }
        val sizes = mapOf("m1" to 100L, "m2" to 250L)
        val manager = ModelManager(catalog, dirSizeBytes = { sizes.getValue(it) }, deleteModelDir = { true })

        val usage = manager.usage()

        assertEquals(setOf("m1" to 100L, "m2" to 250L), usage.map { it.descriptor.modelId to it.sizeBytes }.toSet())
        assertEquals(350L, manager.totalBytes())
    }

    @Test
    fun removeDropsTheModelFromTheCatalogAndDeletesItsFiles() {
        val catalog = ModelCatalog().apply { add(testDescriptor("m1", "eng")) }
        var deleteCalledWith: String? = null
        val manager =
            ModelManager(
                catalog,
                dirSizeBytes = { 0L },
                deleteModelDir = { id ->
                    deleteCalledWith = id
                    true
                },
            )

        val result = manager.remove("m1")

        assertTrue(result.removedFromCatalog)
        assertTrue(result.filesDeleted)
        assertFalse(result.engineUnloaded)
        assertEquals("m1", deleteCalledWith)
        assertNull(catalog.get("m1"))
    }

    @Test
    fun removeReportsWhateverTheInjectedDeleteReturns() {
        val catalog = ModelCatalog().apply { add(testDescriptor("m1", "eng")) }
        val manager = ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { false })

        val result = manager.remove("m1")

        assertFalse(result.filesDeleted)
        // Catalog + override cleanup still happen even if there was nothing on disk to delete.
        assertNull(catalog.get("m1"))
    }

    @Test
    fun removeOfAnUncatalogedModelIsANoOpThatTouchesNothing() {
        val catalog = ModelCatalog()
        var deleteCalled = false
        val manager =
            ModelManager(
                catalog,
                dirSizeBytes = { 0L },
                deleteModelDir = {
                    deleteCalled = true
                    true
                },
            )

        val result = manager.remove("ghost")

        assertFalse(result.removedFromCatalog)
        assertFalse(result.filesDeleted)
        assertFalse(result.engineUnloaded)
        assertFalse(deleteCalled)
    }

    @Test
    fun removeClearsTheSavedOverrideWhenTheStoreSupportsIt() {
        val catalog = ModelCatalog().apply { add(testDescriptor("m1", "eng")) }
        val overrideStore = FakeClearableOverrideStore().apply { put("m1", "eng") }
        val manager =
            ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { true }, overrideStore = overrideStore)

        manager.remove("m1")

        assertNull(overrideStore.get("m1"))
    }

    @Test
    fun removeToleratesAnOverrideStoreThatCannotBeCleared() {
        val catalog = ModelCatalog().apply { add(testDescriptor("m1", "eng")) }
        val overrideStore = PlainOverrideStore().apply { put("m1", "eng") }
        val manager =
            ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { true }, overrideStore = overrideStore)

        // No exception, and — since this store can't drop entries — the stale mapping stays;
        // that's the documented, safe degradation for a non-Clearable OverrideStore.
        val result = manager.remove("m1")

        assertTrue(result.removedFromCatalog)
        assertEquals("eng", overrideStore.get("m1"))
    }

    @Test
    fun removeUnloadsTheEngineWhenTheModelIsCurrentlyLoaded() =
        runTest {
            val catalog = ModelCatalog().apply { add(testDescriptor("m1", "a")) }
            val engineA = FakeEngine(id = "a")
            val registry =
                EngineRegistry().apply {
                    register(engineA)
                }
            val engineManager = EngineManager(registry)
            engineManager.switchTo("a", testDescriptor("m1", "a"))

            val manager =
                ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { true }, engineManager = engineManager)

            val result = manager.remove("m1")

            assertTrue(result.engineUnloaded)
            assertEquals(1, engineA.unloadCount)
        }

    @Test
    fun removeLeavesADifferentlyLoadedEngineAlone() =
        runTest {
            val catalog =
                ModelCatalog().apply {
                    add(testDescriptor("m1", "a"))
                    add(testDescriptor("m2", "b"))
                }
            val engineA = FakeEngine(id = "a")
            val engineB = FakeEngine(id = "b")
            val registry =
                EngineRegistry().apply {
                    register(engineA)
                    register(engineB)
                }
            val engineManager = EngineManager(registry)
            engineManager.switchTo("b", testDescriptor("m2", "b"))

            val manager =
                ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { true }, engineManager = engineManager)

            val result = manager.remove("m1")

            assertFalse(result.engineUnloaded)
            assertEquals(0, engineB.unloadCount)
        }
}
