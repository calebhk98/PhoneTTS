package com.phonetts.integration

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.registry.EngineLoader
import com.phonetts.core.registry.EngineRegistry
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The payoff test. All five engine modules are on this module's classpath, and NOTHING here
 * names any of them — yet ServiceLoader discovers all five and seeds the registry. This is the
 * single-source-of-truth guarantee proven end-to-end: adding a model is adding a module,
 * removing one is deleting a module, and shared code never changes.
 */
class AllEnginesDiscoveryTest {
    private val expectedEngineCount = 5

    private fun context() = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

    @Test
    fun discoversAllFiveEngineProviders() {
        val providers = EngineLoader.discoverProviders()
        assertEquals(expectedEngineCount, providers.size, "expected all five engine modules on the classpath")

        val ids = providers.map { it.engineId }
        assertEquals(ids.size, ids.toSet().size, "engine ids must be unique: $ids")
        assertTrue(ids.none { it.isBlank() }, "engine ids must be non-blank: $ids")
    }

    @Test
    fun seedsEveryDiscoveredEngineIntoTheRegistry() {
        val registry = EngineRegistry()
        EngineLoader.seed(registry, context())

        val engines = registry.list()
        assertEquals(expectedEngineCount, engines.size)
        assertTrue(engines.all { it.id.isNotBlank() && it.displayName.isNotBlank() })
    }
}
