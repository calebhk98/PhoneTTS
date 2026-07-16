package com.phonetts.engines.melotts

import com.phonetts.engines.common.testing.assertSeedsEngineIntoRegistry
import com.phonetts.engines.common.testing.assertSingleDiscoveredProvider
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the discovery seam (spec §5.6): [MeloEngineProvider] is found purely via the
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` resource in this module, with no
 * shared list naming MeloTTS anywhere.
 */
class MeloEngineProviderTest {
    private fun context() = engineContext()

    @Test
    fun `MeloEngineProvider is discoverable via ServiceLoader`() {
        assertSingleDiscoveredProvider<MeloEngineProvider>(MeloEngine.ENGINE_ID)
    }

    @Test
    fun `discovered provider seeds a working MeloEngine into the registry`() {
        val engine = assertSeedsEngineIntoRegistry(MeloEngine.ENGINE_ID, context())

        assertEquals("MeloTTS", engine.displayName)
    }
}
