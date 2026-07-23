package com.phonetts.core.engine

import com.phonetts.core.model.ModelDescriptor

/**
 * The discovery seam. Each engine module ships one [EngineProvider] and registers it via
 * `java.util.ServiceLoader` (a `META-INF/services/com.phonetts.core.engine.EngineProvider`
 * resource inside that module). At startup the app loads every provider on the classpath and
 * seeds the registry from them - so ADDING a model is adding a module, and REMOVING one is
 * deleting a module. No shared list is ever edited; no shared code names any model.
 *
 * Providers must have a public no-arg constructor (ServiceLoader requirement).
 */
interface EngineProvider {
    val engineId: String

    fun create(context: EngineContext): VoiceEngine

    /**
     * Descriptors this provider's engine makes available WITHOUT going through the normal
     * downloaded-bundle → [com.phonetts.core.sideload.ModelImporter] pipeline - e.g. an engine
     * that wraps an already-installed OS service rather than loading weights of its own (see
     * `com.phonetts.app.runtime.SystemTtsEngineProvider`). Called once at startup alongside
     * [create] (see `EngineLoader`/`com.phonetts.app.AppGraph.hydrate`); most providers have
     * nothing to add here, hence the empty default so the existing bundle-backed providers need
     * no change. `suspend` because discovery may itself need to talk to a platform service
     * asynchronously (system TTS engines initialize via an async callback - see
     * `SystemTtsDiscovery`), and per rule 8 this must never block the caller's thread.
     */
    suspend fun builtInDescriptors(context: EngineContext): List<ModelDescriptor> = emptyList()
}
