package com.phonetts.engines.supertonic

import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import com.phonetts.engines.common.testing.assertSingleDiscoveredProvider
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the discovery seam (spec §5.4): the `META-INF/services/com.phonetts.core.engine.
 * EngineProvider` resource in this module must let `EngineLoader` find [SupertonicEngineProvider]
 * via `ServiceLoader`, exactly as the app's real bootstrap does.
 */
class SupertonicEngineProviderTest {
    private val context = engineContext()

    @Test
    fun `provider is discoverable via ServiceLoader`() {
        val provider = assertSingleDiscoveredProvider<SupertonicEngineProvider>(SupertonicEngine.ENGINE_ID)

        val engine = assertCreatesMatchingEngine(provider, context)

        assertEquals(SupertonicEngine.DISPLAY_NAME, engine.displayName)
    }

    @Test
    fun `provider has the no-arg constructor ServiceLoader requires and creates a working engine`() {
        val provider = SupertonicEngineProvider()

        assertEquals(SupertonicEngine.ENGINE_ID, provider.engineId)
        assertCreatesMatchingEngine(provider, context)
    }
}
