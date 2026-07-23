package com.phonetts.engines.melotts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * Discovery seam entry point (spec §5.6): registered via
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` so the app finds MeloTTS purely by
 * having this module on the classpath. Adding/removing MeloTTS is adding/removing this module -
 * no shared list is ever edited.
 */
class MeloEngineProvider : EngineProvider {
    override val engineId: String = MeloEngine.ENGINE_ID

    override fun create(context: EngineContext): VoiceEngine = MeloEngine(context)
}
