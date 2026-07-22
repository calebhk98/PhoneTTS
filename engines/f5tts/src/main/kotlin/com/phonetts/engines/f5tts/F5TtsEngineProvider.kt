package com.phonetts.engines.f5tts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * ServiceLoader entry point for the F5-TTS engine module (see
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` in `src/main/resources`).
 * Registering this provider is the ONLY thing that adds F5-TTS to the app; deleting this module
 * removes it with no other change (spec §1.1.6 / CLAUDE.md rule 5). Requires the public no-arg
 * constructor ServiceLoader needs.
 */
class F5TtsEngineProvider : EngineProvider {
    override val engineId: String = F5TtsEngine.ENGINE_ID

    override fun create(context: EngineContext): VoiceEngine = F5TtsEngine(context)
}
