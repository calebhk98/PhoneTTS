package com.phonetts.core.audio.transform

// An ordered set of transforms, each individually enabled or disabled. This is the whole reason
// transforms are non-destructive: the chain is applied to a COPY of the raw audio on demand, so
// flipping any entry off and re-applying yields the original audio back with zero re-synthesis.
// The chain is immutable — toggling returns a new chain — so it is safe to hold in UI state.

/** One transform plus whether it is currently active. */
data class TransformEntry(
    val transform: AudioTransform,
    val enabled: Boolean,
)

class TransformChain(entries: List<TransformEntry>) {
    // Defensive copy so callers cannot mutate the chain's backing list out from under it.
    val entries: List<TransformEntry> = entries.toList()

    /** Apply every enabled transform, in order, to a copy of [segments]. */
    fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        var current = segments
        for (entry in entries) {
            if (!entry.enabled) continue
            current = entry.transform.apply(current, sampleRate)
        }
        return current
    }

    fun isEnabled(id: String): Boolean = entries.any { it.transform.id == id && it.enabled }

    /** Return a new chain with the transform [id] set to [enabled] (immutable toggle). */
    fun withEnabled(
        id: String,
        enabled: Boolean,
    ): TransformChain =
        TransformChain(
            entries.map { entry ->
                if (entry.transform.id == id) entry.copy(enabled = enabled) else entry
            },
        )

    companion object {
        /** A chain of the given transforms, all initially disabled (opt-in, off by default). */
        fun of(transforms: List<AudioTransform>): TransformChain =
            TransformChain(transforms.map { TransformEntry(it, enabled = false) })
    }
}
