package com.phonetts.engines.kokoro

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * `ServiceLoader` entry point for the Kokoro engine (spec §5.1, [EngineProvider]). Registered via
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` in this module's resources, so the
 * app discovers Kokoro purely by this module being on the classpath — no shared registry edit
 * required to add or remove it (spec §1.1.6). Requires the public no-arg constructor `ServiceLoader`
 * needs; the real [EngineContext] (runtimes, phonemizer) arrives later, in [create].
 */
class KokoroEngineProvider : EngineProvider {
    override val engineId: String = KokoroEngine.ENGINE_ID

    override fun create(context: EngineContext): VoiceEngine = KokoroEngine(context)
}
