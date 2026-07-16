package com.phonetts.engines.piper

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.registry.EngineLoader
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The discovery seam (spec §5.4): [PiperEngineProvider] has the public no-arg constructor
 * `ServiceLoader` requires, is discoverable via the module's
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` resource, and its `create`
 * builds a working [PiperEngine].
 */
class PiperEngineProviderTest {
    @Test
    fun `has the piper engine id and builds a PiperEngine`() {
        val provider = PiperEngineProvider()
        val context = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

        assertEquals("piper", provider.engineId)
        val engine = provider.create(context)
        assertEquals("piper", engine.id)
        assertIs<PiperEngine>(engine)
    }

    @Test
    fun `is discoverable on the classpath via EngineLoader ServiceLoader seeding`() {
        val providers = EngineLoader.discoverProviders(PiperEngineProvider::class.java.classLoader)

        assertTrue(providers.any { it.engineId == "piper" && it is PiperEngineProvider })
    }
}
