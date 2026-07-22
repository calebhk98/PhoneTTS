package com.phonetts.engines.f5tts

import com.phonetts.core.registry.EngineLoader
import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import com.phonetts.engines.common.testing.assertSeedsEngineIntoRegistry
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the discovery seam (spec §5.4): this module's `EngineProvider` is found via
 * `ServiceLoader` with no shared code naming F5-TTS, and `create()` builds a working engine from
 * an [com.phonetts.core.engine.EngineContext].
 */
class F5TtsEngineProviderTest {
    @Test
    fun `provider is discoverable via ServiceLoader on this module's classpath`() {
        val ids = EngineLoader.discoverProviders().map { it.engineId }

        assertEquals(listOf(F5TtsEngine.ENGINE_ID), ids)
    }

    @Test
    fun `provider engineId matches the engine it creates`() {
        assertCreatesMatchingEngine(F5TtsEngineProvider(), engineContext())
    }

    @Test
    fun `create builds a working F5TtsEngine exposing at least one voice`() {
        val engine = F5TtsEngineProvider().create(engineContext())

        assertEquals(F5TtsEngine.ENGINE_ID, engine.id)
        assertTrue(engine.voices().isNotEmpty())
    }

    @Test
    fun `EngineLoader seed registers a working f5tts engine`() {
        assertSeedsEngineIntoRegistry(F5TtsEngine.ENGINE_ID)
    }
}
