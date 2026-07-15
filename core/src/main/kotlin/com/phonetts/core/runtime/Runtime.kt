package com.phonetts.core.runtime

/**
 * A pluggable inference backend (spec §5.3). Most engines run on ONNX Runtime; at least
 * one (CosyVoice2) likely needs a different, LLM-style runtime. Keeping runtimes behind
 * this interface means adding one later touches nothing else.
 *
 * The concrete tensor/session shape is runtime-specific and is defined when the first
 * real runtime lands in Phase 2 — deliberately not modelled here, so the skeleton does
 * not bake ONNX assumptions into the seam. This interface only carries what the
 * [RuntimeRegistry] needs: identity and availability.
 */
interface Runtime {
    val id: String

    /** True if this runtime's native libraries are present and usable on this device. */
    fun isAvailable(): Boolean
}
