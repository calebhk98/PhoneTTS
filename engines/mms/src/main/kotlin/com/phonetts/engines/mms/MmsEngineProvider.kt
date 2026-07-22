package com.phonetts.engines.mms

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * The MMS engine's discovery seam entry point (spec §5.4). Registered via
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` so `EngineLoader` picks it up from
 * the classpath with no shared code naming "MMS" anywhere else. Requires the public no-arg
 * constructor `ServiceLoader` needs.
 */
class MmsEngineProvider : EngineProvider {
    override val engineId: String = MmsEngine.ENGINE_ID

    override fun create(context: EngineContext): VoiceEngine = MmsEngine(context)
}
