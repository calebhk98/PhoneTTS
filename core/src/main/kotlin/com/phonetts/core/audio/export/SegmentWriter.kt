package com.phonetts.core.audio.export

// Incremental, bounded-memory sink for one export run. [AudioEncoder] feeds it settled segments as
// the synthesis flow arrives (and as streaming transforms release them), so no encoder ever holds
// the whole utterance as a List<FloatArray> on the heap - the root cause of the long-document OOM
// this replaces. Each concrete format implements one of these instead of a buffer-everything
// `writeEncoded(List)`.
interface SegmentWriter {
    /** Encode and commit one already-settled segment now. Must not retain more than it has to. */
    fun write(segment: FloatArray)

    /** Finalize the container (header/footer, flush, copy out) and release any scratch resources. */
    fun close()
}
