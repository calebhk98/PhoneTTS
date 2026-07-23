package com.phonetts.core.registry

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.testing.FakeEngineProvider
import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Proves the discovery seam: an [com.phonetts.core.engine.EngineProvider] registered via
 * ServiceLoader (see core/src/test/resources/META-INF/services/) is discovered and seeded into
 * the registry with no shared code naming it. This is the mechanism that lets a real engine
 * module drop in - or be deleted - with zero changes elsewhere.
 */
class EngineLoaderTest {
    private fun context() = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

    @Test
    fun discoversProvidersOnTheClasspath() {
        val ids = EngineLoader.discoverProviders().map { it.engineId }
        assertEquals(listOf(FakeEngineProvider.PROVIDED_ID), ids)
    }

    @Test
    fun seedsDiscoveredEnginesIntoTheRegistry() {
        val registry = EngineRegistry()
        EngineLoader.seed(registry, context())
        assertNotNull(registry.get(FakeEngineProvider.PROVIDED_ID))
        assertEquals(listOf(FakeEngineProvider.PROVIDED_ID), registry.list().map { it.id })
    }
}
