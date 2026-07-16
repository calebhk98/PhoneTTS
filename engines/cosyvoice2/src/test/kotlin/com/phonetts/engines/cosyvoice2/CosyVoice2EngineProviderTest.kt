package com.phonetts.engines.cosyvoice2

import com.phonetts.core.registry.EngineLoader
import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the discovery seam (spec §5.4): this module's `EngineProvider` is found via
 * `ServiceLoader` with no shared code naming CosyVoice2, and `create()` builds a working
 * engine from an [com.phonetts.core.engine.EngineContext].
 */
class CosyVoice2EngineProviderTest {
    @Test
    fun `provider is discoverable via ServiceLoader on this module's classpath`() {
        val ids = EngineLoader.discoverProviders().map { it.engineId }

        assertEquals(listOf(CosyVoice2Engine.ENGINE_ID), ids)
    }

    @Test
    fun `provider engineId matches the engine it creates`() {
        assertCreatesMatchingEngine(CosyVoice2EngineProvider(), emptyContext())
    }

    @Test
    fun `create builds a working CosyVoice2Engine exposing at least one voice`() {
        val provider = CosyVoice2EngineProvider()

        val engine = provider.create(emptyContext())

        assertEquals(CosyVoice2Engine.ENGINE_ID, engine.id)
        assertTrue(engine.voices().isNotEmpty())
    }
}
