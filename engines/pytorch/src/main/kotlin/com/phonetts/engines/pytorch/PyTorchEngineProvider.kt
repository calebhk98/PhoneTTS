package com.phonetts.engines.pytorch

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * ServiceLoader entry point for the PyTorch engine module (see
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` in `src/main/resources`).
 * Registering this provider is the ONLY thing that adds the PyTorch engine to the app; deleting
 * this module removes it with no other change (spec §1.1.6 / CLAUDE.md rule 5). Requires the
 * public no-arg constructor ServiceLoader needs.
 */
class PyTorchEngineProvider : EngineProvider {
    override val engineId: String = PyTorchEngine.ENGINE_ID

    // context is unused: PyTorchEngine has no runtime to look up (see its class kdoc) - accepted
    // only to satisfy EngineProvider's shared signature, same as every other provider.
    override fun create(context: EngineContext): VoiceEngine = PyTorchEngine()
}
