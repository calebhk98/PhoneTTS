package com.phonetts.engines.pytorch

import com.phonetts.core.registry.EngineLoader
import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import com.phonetts.engines.common.testing.assertSeedsEngineIntoRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the discovery seam (spec §5.4) for this deliberately-inert engine: its `EngineProvider` is
 * still found via `ServiceLoader` and still seeds a working (if permanently unmatched-anything)
 * [PyTorchEngine] into the registry - a provider is safe to keep exactly because its engine always
 * fails closed (see [PyTorchEngine]'s kdoc).
 */
class PyTorchEngineProviderTest {
    @Test
    fun `provider is discoverable via ServiceLoader on this module's classpath`() {
        val ids = EngineLoader.discoverProviders().map { it.engineId }

        assertEquals(listOf(PyTorchEngine.ENGINE_ID), ids)
    }

    @Test
    fun `provider engineId matches the engine it creates`() {
        assertCreatesMatchingEngine(PyTorchEngineProvider())
    }

    @Test
    fun `EngineLoader seeds a working PyTorchEngine into a fresh registry`() {
        val engine = assertSeedsEngineIntoRegistry(PyTorchEngine.ENGINE_ID)

        assertTrue(engine.voices().isEmpty(), "expected the honest empty voice list, never a fabricated default")
    }
}
