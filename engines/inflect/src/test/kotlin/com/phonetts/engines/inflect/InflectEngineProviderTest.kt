package com.phonetts.engines.inflect

import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import com.phonetts.engines.common.testing.assertSingleDiscoveredProvider
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the discovery seam (spec §5.4): the `META-INF/services/com.phonetts.core.engine.
 * EngineProvider` resource in this module must let `EngineLoader` find [InflectEngineProvider] via
 * `ServiceLoader`, exactly as the app's real bootstrap does - the registration a new engine module
 * is easiest to forget, and the one that makes it ship in no build at all when it is missing.
 */
class InflectEngineProviderTest {
    private val context = engineContext()

    @Test
    fun `provider is discoverable via ServiceLoader`() {
        val provider = assertSingleDiscoveredProvider<InflectEngineProvider>(InflectEngine.ENGINE_ID)

        val engine = assertCreatesMatchingEngine(provider, context)

        assertEquals(InflectEngine.DISPLAY_NAME, engine.displayName)
    }

    @Test
    fun `provider has the no-arg constructor ServiceLoader requires and creates a working engine`() {
        val provider = InflectEngineProvider()

        assertEquals(InflectEngine.ENGINE_ID, provider.engineId)
        assertCreatesMatchingEngine(provider, context)
    }
}
