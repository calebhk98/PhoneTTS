package com.phonetts.engines.outetts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * ServiceLoader entry point for the OuteTTS engine module (see
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` in `src/main/resources`).
 * Registering this provider is the ONLY thing that adds this engine to the app; deleting this
 * module removes it with no other change (spec §1.1.6 / CLAUDE.md rule 5). Requires the public
 * no-arg constructor ServiceLoader needs.
 */
class OuteTtsEngineProvider : EngineProvider {
    override val engineId: String = OuteTtsEngine.ENGINE_ID

    override fun create(context: EngineContext): VoiceEngine = OuteTtsEngine(context)
}
