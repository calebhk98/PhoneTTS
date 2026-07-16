package com.phonetts.engines.kittentts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.registry.EngineLoader
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Covers the discovery seam (spec §5.4): the `META-INF/services/com.phonetts.core.engine.
 * EngineProvider` resource in this module must let [EngineLoader] find [KittenEngineProvider]
 * via `ServiceLoader`, exactly as the app's real bootstrap does — and a directly constructed
 * provider (the no-arg-constructor path `ServiceLoader` relies on) must work identically.
 */
class KittenEngineProviderTest {
    private val context = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

    @Test
    fun `provider is discoverable via ServiceLoader`() {
        val providers = EngineLoader.discoverProviders()

        val kittenProvider = assertNotNull(providers.singleOrNull { it.engineId == KittenEngine.ENGINE_ID })
        val engine = kittenProvider.create(context)
        assertEquals(KittenEngine.ENGINE_ID, engine.id)
        assertEquals(KittenEngine.DISPLAY_NAME, engine.displayName)
    }

    @Test
    fun `provider has the no-arg constructor ServiceLoader requires and creates a working engine`() {
        val provider = KittenEngineProvider()

        assertEquals(KittenEngine.ENGINE_ID, provider.engineId)
        val engine = provider.create(context)
        assertEquals(KittenEngine.ENGINE_ID, engine.id)
    }
}
