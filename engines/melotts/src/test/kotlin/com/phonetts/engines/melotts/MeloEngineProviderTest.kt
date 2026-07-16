package com.phonetts.engines.melotts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.registry.EngineLoader
import com.phonetts.core.registry.EngineRegistry
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Proves the discovery seam (spec §5.6): [MeloEngineProvider] is found purely via the
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` resource in this module, with no
 * shared list naming MeloTTS anywhere.
 */
class MeloEngineProviderTest {
    private fun context() = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

    @Test
    fun `MeloEngineProvider is discoverable via ServiceLoader`() {
        val providers = EngineLoader.discoverProviders()

        val melo = providers.singleOrNull { it.engineId == MeloEngine.ENGINE_ID }
        assertNotNull(melo, "MeloEngineProvider must be registered via META-INF/services")
        assertIs<MeloEngineProvider>(melo)
    }

    @Test
    fun `discovered provider seeds a working MeloEngine into the registry`() {
        val registry = EngineRegistry()

        EngineLoader.seed(registry, context())

        val engine = assertNotNull(registry.get(MeloEngine.ENGINE_ID))
        assertEquals("MeloTTS", engine.displayName)
    }
}
