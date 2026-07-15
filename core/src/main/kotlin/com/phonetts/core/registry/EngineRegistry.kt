package com.phonetts.core.registry

import com.phonetts.core.engine.VoiceEngine

/**
 * Runtime, mutable registry of [VoiceEngine] registrations — the single source of truth for
 * "what engines/models exist" (spec §5.4). Built-in engines are just pre-seeded registrations
 * made at startup; there is no built-in-vs-sideloaded branch here or downstream. Origin is
 * recorded only on [com.phonetts.core.model.ModelDescriptor], for display.
 */
class EngineRegistry {
    private val engines = LinkedHashMap<String, VoiceEngine>()

    /** Register [engine], replacing any existing registration under the same id. */
    @Synchronized
    fun register(engine: VoiceEngine) {
        engines[engine.id] = engine
    }

    /** Remove the engine registered under [engineId], if any. A no-op if it isn't registered. */
    @Synchronized
    fun unregister(engineId: String) {
        engines.remove(engineId)
    }

    /** The engine registered under [engineId], or null if none is registered. */
    @Synchronized
    fun get(engineId: String): VoiceEngine? = engines[engineId]

    /** All currently registered engines, in registration order. */
    @Synchronized
    fun list(): List<VoiceEngine> = engines.values.toList()
}
