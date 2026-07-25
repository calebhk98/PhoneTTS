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
 * The payoff test. EVERY engine module is on this module's classpath, and NOTHING here names any
 * of them - yet ServiceLoader discovers them all and seeds the registry. This is the
 * single-source-of-truth guarantee proven end-to-end: adding a model is adding a module,
 * removing one is deleting a module, and shared code never changes.
 *
 * The expected count comes from Gradle (the count of :engines:* modules on disk), NOT from a
 * constant here. It used to be a hardcoded 5 while 15 engine modules existed, so this test - the
 * one thing guarding the drop-in guarantee - was silently ignoring ten of them.
 */
class AllEnginesDiscoveryTest {
    private val expectedEngineCount =
        System.getProperty(EXPECTED_COUNT_PROPERTY)?.toIntOrNull()
            ?: error("$EXPECTED_COUNT_PROPERTY not set - run via Gradle, which derives it from the module list")

    private fun context() = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

    @Test
    fun discoversEveryEngineProviderOnTheClasspath() {
        val providers = EngineLoader.discoverProviders()
        assertEquals(expectedEngineCount, providers.size, "expected every engine module on the classpath")

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

    private companion object {
        const val EXPECTED_COUNT_PROPERTY = "phonetts.expectedEngineCount"
    }
}
