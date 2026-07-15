package com.phonetts.core.testing

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine

/**
 * A ServiceLoader-discoverable provider used only to prove the discovery seam. It has the
 * required public no-arg constructor and yields a [FakeEngine]. A matching
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` resource in the core test source
 * set registers it.
 */
class FakeEngineProvider : EngineProvider {
    override val engineId: String = PROVIDED_ID

    override fun create(context: EngineContext): VoiceEngine = FakeEngine(id = PROVIDED_ID)

    companion object {
        const val PROVIDED_ID = "fake-provided"
    }
}
