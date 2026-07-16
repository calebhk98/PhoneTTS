package com.phonetts.core.registry

import com.phonetts.core.runtime.Runtime

/**
 * Minimal id -> [Runtime] map so a second runtime (e.g. an LLM-style backend for CosyVoice2)
 * can slot in later without touching engine code (spec §5.3).
 */
class RuntimeRegistry {
    private val runtimes = LinkedHashMap<String, Runtime>()

    /** Register [runtime], replacing any existing registration under the same id. */
    @Synchronized
    fun register(runtime: Runtime) {
        runtimes[runtime.id] = runtime
    }

    /** The runtime registered under [runtimeId], or null if none is registered. */
    @Synchronized
    fun get(runtimeId: String): Runtime? = runtimes[runtimeId]

    /** All currently registered runtimes, in registration order. */
    @Synchronized
    fun list(): List<Runtime> = runtimes.values.toList()
}
