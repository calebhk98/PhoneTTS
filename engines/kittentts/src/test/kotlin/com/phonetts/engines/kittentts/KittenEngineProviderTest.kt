package com.phonetts.engines.kittentts

import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import com.phonetts.engines.common.testing.assertSingleDiscoveredProvider
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the discovery seam (spec §5.4): the `META-INF/services/com.phonetts.core.engine.
 * EngineProvider` resource in this module must let `EngineLoader` find [KittenEngineProvider]
 * via `ServiceLoader`, exactly as the app's real bootstrap does — and a directly constructed
 * provider (the no-arg-constructor path `ServiceLoader` relies on) must work identically.
 */
class KittenEngineProviderTest {
    private val context = engineContext()

    @Test
    fun `provider is discoverable via ServiceLoader`() {
        val kittenProvider = assertSingleDiscoveredProvider<KittenEngineProvider>(KittenEngine.ENGINE_ID)

        val engine = assertCreatesMatchingEngine(kittenProvider, context)

        assertEquals(KittenEngine.DISPLAY_NAME, engine.displayName)
    }

    @Test
    fun `provider has the no-arg constructor ServiceLoader requires and creates a working engine`() {
        val provider = KittenEngineProvider()

        assertEquals(KittenEngine.ENGINE_ID, provider.engineId)
        assertCreatesMatchingEngine(provider, context)
    }
}
