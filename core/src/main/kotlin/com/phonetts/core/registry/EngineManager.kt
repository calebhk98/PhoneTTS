package com.phonetts.core.registry

import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps exactly one [VoiceEngine] loaded at a time (spec §5.5). On [switchTo], the
 * previously loaded engine (if any) is unloaded before the next one is loaded, so peak
 * memory never holds two models' weights at once. Concurrent switches are serialized with
 * a [Mutex] so an in-flight switch always finishes its unload-then-load before the next
 * one starts.
 */
class EngineManager(private val registry: EngineRegistry) {
    private val mutex = Mutex()

    /** The engine currently loaded, or null if none has been loaded yet. */
    var currentEngine: VoiceEngine? = null
        private set

    /** The descriptor the current engine was last loaded with, or null if none is loaded. */
    var currentDescriptor: ModelDescriptor? = null
        private set

    /**
     * Switch the loaded engine to [engineId], loading it with [descriptor]. The previously
     * loaded engine, if any, is unloaded first. Throws [IllegalStateException] if [engineId]
     * is not registered in [registry]; the currently loaded engine is left untouched in that
     * case.
     */
    suspend fun switchTo(
        engineId: String,
        descriptor: ModelDescriptor,
    ) {
        mutex.withLock {
            val next = registry.get(engineId) ?: error("no engine registered under id '$engineId'")
            currentEngine?.unload()
            // Clear state before load: if load() throws (corrupt/oversized model, missing asset),
            // we must NOT leave currentEngine pointing at the already-unloaded previous engine, or a
            // caller would treat a non-null currentEngine as "ready" and get a crash on synthesize().
            currentEngine = null
            currentDescriptor = null
            next.load(descriptor)
            currentEngine = next
            currentDescriptor = descriptor
        }
    }
}
