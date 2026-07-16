package com.phonetts.engines.kokoro

import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import com.phonetts.engines.common.testing.assertSeedsEngineIntoRegistry
import com.phonetts.engines.common.testing.assertSingleDiscoveredProvider
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test

/**
 * Proves the discovery seam end to end for this module: [KokoroEngineProvider] is registered via
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` (in this module's resources) and is
 * found by the same generic `EngineLoader` every engine module is discovered through - no shared
 * code names "kokoro" anywhere in `:core`.
 */
class KokoroEngineProviderTest {
    private fun context() = engineContext()

    @Test
    fun isDiscoverableViaServiceLoader() {
        assertSingleDiscoveredProvider<KokoroEngineProvider>(KokoroEngine.ENGINE_ID)
    }

    @Test
    fun createdEnginesIdMatchesTheProvidersEngineId() {
        assertCreatesMatchingEngine(KokoroEngineProvider(), context())
    }

    @Test
    fun seedsIntoTheEngineRegistryLikeAnyOtherProvider() {
        assertSeedsEngineIntoRegistry(KokoroEngine.ENGINE_ID, context())
    }
}
