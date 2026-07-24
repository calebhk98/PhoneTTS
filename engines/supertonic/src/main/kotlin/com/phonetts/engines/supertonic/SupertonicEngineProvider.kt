package com.phonetts.engines.supertonic

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * Discovery seam for Supertonic (spec §5.4). Registered via
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` in this module's resources so
 * [com.phonetts.core.registry.EngineLoader] finds it on the classpath with no shared code naming
 * this engine. Requires the public no-arg constructor `ServiceLoader` needs.
 */
class SupertonicEngineProvider : EngineProvider {
    override val engineId: String = SupertonicEngine.ENGINE_ID

    override fun create(context: EngineContext): VoiceEngine = SupertonicEngine(context)
}
