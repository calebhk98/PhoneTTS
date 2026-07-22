package com.phonetts.engines.executorch

import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import com.phonetts.engines.common.testing.assertSeedsEngineIntoRegistry
import com.phonetts.engines.common.testing.assertSingleDiscoveredProvider
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test

/**
 * Proves the discovery seam end to end for this module: [ExecuTorchKokoroEngineProvider] is
 * registered via `META-INF/services/com.phonetts.core.engine.EngineProvider` (in this module's
 * resources) and is found by the same generic `EngineLoader` every engine module is discovered
 * through — no shared code names "executorch-kokoro" anywhere in `:core`.
 */
class ExecuTorchKokoroEngineProviderTest {
    private fun context() = engineContext()

    @Test
    fun isDiscoverableViaServiceLoader() {
        assertSingleDiscoveredProvider<ExecuTorchKokoroEngineProvider>(ExecuTorchKokoroEngine.ENGINE_ID)
    }

    @Test
    fun createdEnginesIdMatchesTheProvidersEngineId() {
        assertCreatesMatchingEngine(ExecuTorchKokoroEngineProvider(), context())
    }

    @Test
    fun seedsIntoTheEngineRegistryLikeAnyOtherProvider() {
        assertSeedsEngineIntoRegistry(ExecuTorchKokoroEngine.ENGINE_ID, context())
    }
}
