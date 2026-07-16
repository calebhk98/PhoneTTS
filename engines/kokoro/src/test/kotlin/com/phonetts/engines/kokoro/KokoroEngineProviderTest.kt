package com.phonetts.engines.kokoro

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
 * Proves the discovery seam end to end for this module: [KokoroEngineProvider] is registered via
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` (in this module's resources) and is
 * found by the same generic [EngineLoader] every engine module is discovered through - no shared
 * code names "kokoro" anywhere in `:core`.
 */
class KokoroEngineProviderTest {
    private fun context() = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

    @Test
    fun isDiscoverableViaServiceLoader() {
        val providers = EngineLoader.discoverProviders()

        val kokoroProvider = providers.singleOrNull { it.engineId == KokoroEngine.ENGINE_ID }
        assertNotNull(kokoroProvider, "expected exactly one provider with engineId '${KokoroEngine.ENGINE_ID}'")
        assertIs<KokoroEngineProvider>(kokoroProvider)
    }

    @Test
    fun createdEnginesIdMatchesTheProvidersEngineId() {
        val provider = KokoroEngineProvider()

        val engine = provider.create(context())

        assertEquals(provider.engineId, engine.id)
    }

    @Test
    fun seedsIntoTheEngineRegistryLikeAnyOtherProvider() {
        val registry = EngineRegistry()

        EngineLoader.seed(registry, context())

        assertNotNull(registry.get(KokoroEngine.ENGINE_ID))
    }
}
