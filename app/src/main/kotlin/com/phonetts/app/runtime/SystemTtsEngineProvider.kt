package com.phonetts.app.runtime

import com.phonetts.app.AppPlatformServices
import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelDescriptor

/**
 * The system-TTS engine's discovery seam entry point (issue #77), the same shape every other
 * engine uses (see e.g. `com.phonetts.engines.piper.PiperEngineProvider`): registered via
 * `META-INF/services/com.phonetts.core.engine.EngineProvider` so `EngineLoader` picks it up from
 * `:app`'s own classpath resources with no shared code naming "SystemTts" anywhere else
 * (CLAUDE.md rule 5).
 *
 * The one thing that makes this engine different from a `engines/*` module is that it wraps an
 * Android OS service rather than loading weights of its own, so it needs an application
 * `Context`. That comes through [EngineContext.platform] — cast to this app's concrete
 * [AppPlatformServices] — rather than :core growing an Android dependency. [builtInDescriptors]
 * is this provider's other non-default hook: unlike a downloaded-bundle engine, "its" models are
 * discovered directly from the OS (see [SystemTtsDiscovery]), never resolved from a bundle on
 * disk, so they are surfaced here instead of through the normal sideload/import pipeline.
 *
 * Requires the public no-arg constructor `ServiceLoader` needs.
 */
class SystemTtsEngineProvider : EngineProvider {
    override val engineId: String = SystemTtsEngine.ENGINE_ID

    override fun create(context: EngineContext): VoiceEngine = SystemTtsEngine(platformOf(context).appContext)

    override suspend fun builtInDescriptors(context: EngineContext): List<ModelDescriptor> =
        SystemTtsDiscovery.discover(platformOf(context).appContext, engineId)

    private fun platformOf(context: EngineContext): AppPlatformServices =
        context.platform as? AppPlatformServices
            ?: error("$engineId: EngineContext.platform must be an AppPlatformServices instance")
}
