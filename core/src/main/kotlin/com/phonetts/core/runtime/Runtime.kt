package com.phonetts.core.runtime

/**
 * A pluggable inference backend (spec §5.3). Most engines run on ONNX Runtime; at least
 * one (CosyVoice2) likely needs a different, LLM-style runtime. Keeping runtimes behind
 * this interface means adding one later touches nothing else — an engine asks the
 * [com.phonetts.core.registry.RuntimeRegistry] for the runtime it wants by id, so no shared
 * code ever branches on which backend a model uses.
 */
interface Runtime {
    val id: String

    /** True if this runtime's native libraries are present and usable on this device. */
    fun isAvailable(): Boolean

    /** Load a model graph from an on-device path into a ready-to-run [InferenceSession]. */
    fun createSession(
        modelPath: String,
        options: RuntimeOptions = RuntimeOptions(),
    ): InferenceSession
}

/** Backend-agnostic knobs for session creation. Concrete runtimes read what they understand. */
data class RuntimeOptions(
    val intraOpThreads: Int = 1,
    val extras: Map<String, String> = emptyMap(),
)
