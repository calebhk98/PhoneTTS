package com.phonetts.engines.outetts

import com.phonetts.core.registry.EngineLoader
import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the discovery seam (spec §5.4): this module's `EngineProvider` is found via
 * `ServiceLoader` with no shared code naming it, and `create()` builds a working engine from an
 * [com.phonetts.core.engine.EngineContext].
 */
class OuteTtsEngineProviderTest {
    @Test
    fun `provider is discoverable via ServiceLoader on this module's classpath`() {
        val ids = EngineLoader.discoverProviders().map { it.engineId }

        assertEquals(listOf(OuteTtsEngine.ENGINE_ID), ids)
    }

    @Test
    fun `provider engineId matches the engine it creates`() {
        assertCreatesMatchingEngine(OuteTtsEngineProvider(), emptyContext())
    }

    @Test
    fun `create builds a working OuteTtsEngine`() {
        val provider = OuteTtsEngineProvider()

        val engine = provider.create(emptyContext())

        assertEquals(OuteTtsEngine.ENGINE_ID, engine.id)
        assertTrue(engine.voices().isEmpty(), "an unloaded engine has no session-derived voices yet")
    }
}
