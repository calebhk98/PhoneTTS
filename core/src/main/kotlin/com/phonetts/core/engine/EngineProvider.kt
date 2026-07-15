package com.phonetts.core.engine

/**
 * The discovery seam. Each engine module ships one [EngineProvider] and registers it via
 * `java.util.ServiceLoader` (a `META-INF/services/com.phonetts.core.engine.EngineProvider`
 * resource inside that module). At startup the app loads every provider on the classpath and
 * seeds the registry from them — so ADDING a model is adding a module, and REMOVING one is
 * deleting a module. No shared list is ever edited; no shared code names any model.
 *
 * Providers must have a public no-arg constructor (ServiceLoader requirement).
 */
interface EngineProvider {
    val engineId: String

    fun create(context: EngineContext): VoiceEngine
}
