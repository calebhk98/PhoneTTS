package com.phonetts.engines.mms

import com.phonetts.core.registry.EngineLoader
import com.phonetts.engines.common.testing.assertCreatesMatchingEngine
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The discovery seam (spec §5.4): [MmsEngineProvider] has the public no-arg constructor
 * `ServiceLoader` requires, is discoverable via the module's
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` resource, and its `create`
 * builds a working [MmsEngine].
 */
class MmsEngineProviderTest {
    @Test
    fun `has the mms engine id and builds an MmsEngine`() {
        val provider = MmsEngineProvider()

        assertEquals("mms", provider.engineId)
        val engine = assertCreatesMatchingEngine(provider, engineContext())
        assertIs<MmsEngine>(engine)
    }

    @Test
    fun `is discoverable on the classpath via EngineLoader ServiceLoader seeding`() {
        val providers = EngineLoader.discoverProviders(MmsEngineProvider::class.java.classLoader)

        assertTrue(providers.any { it.engineId == "mms" && it is MmsEngineProvider })
    }
}
