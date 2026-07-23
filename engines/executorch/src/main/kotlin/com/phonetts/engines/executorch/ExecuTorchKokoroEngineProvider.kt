package com.phonetts.engines.executorch

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * `ServiceLoader` entry point for the Kokoro-on-ExecuTorch engine (spec §5.1, [EngineProvider]).
 * Registered via `META-INF/services/com.phonetts.core.engine.EngineProvider` in this module's
 * resources, so the app discovers it purely by this module being on the classpath - no shared
 * registry edit required to add or remove it (spec §1.1.6).
 */
class ExecuTorchKokoroEngineProvider : EngineProvider {
    override val engineId: String = ExecuTorchKokoroEngine.ENGINE_ID

    override fun create(context: EngineContext): VoiceEngine = ExecuTorchKokoroEngine(context)
}
