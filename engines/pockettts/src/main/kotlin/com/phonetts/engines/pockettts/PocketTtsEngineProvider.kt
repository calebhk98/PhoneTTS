package com.phonetts.engines.pockettts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * Discovery seam for Pocket TTS (spec §5.4). Registered via
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` in this module's resources so
 * [com.phonetts.core.registry.EngineLoader] finds it on the classpath with no shared code
 * naming this engine. Requires the public no-arg constructor `ServiceLoader` needs.
 */
class PocketTtsEngineProvider : EngineProvider {
    override val engineId: String = PocketTtsEngine.ENGINE_ID

    override fun create(context: EngineContext): VoiceEngine = PocketTtsEngine(context)
}
