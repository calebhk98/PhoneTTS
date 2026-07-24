package com.phonetts.engines.pockettts

import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import com.phonetts.engines.common.testing.assertSingleDiscoveredProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the discovery seam (spec §5.4): the `META-INF/services/com.phonetts.core.engine.
 * EngineProvider` resource in this module must let `EngineLoader` find [PocketTtsEngineProvider]
 * via `ServiceLoader`, exactly as the app's real bootstrap does.
 */
class PocketTtsEngineProviderTest {
    private val context = emptyContext()

    @Test
    fun `provider is discoverable via ServiceLoader`() {
        val provider = assertSingleDiscoveredProvider<PocketTtsEngineProvider>(PocketTtsEngine.ENGINE_ID)

        val engine = assertCreatesMatchingEngine(provider, context)

        assertEquals(PocketTtsEngine.DISPLAY_NAME, engine.displayName)
    }

    @Test
    fun `provider has the no-arg constructor ServiceLoader requires and creates a working engine`() {
        val provider = PocketTtsEngineProvider()

        assertEquals(PocketTtsEngine.ENGINE_ID, provider.engineId)
        assertCreatesMatchingEngine(provider, context)
    }
}
