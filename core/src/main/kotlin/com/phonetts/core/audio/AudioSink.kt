package com.phonetts.core.audio

/**
 * Streaming half of the dual-consumer audio layer (spec §6.1, §11.4). `synthesize()` returns
 * a single `Flow<FloatArray>`; [StreamingConsumer] drives it into whatever implements this
 * contract. The real `AudioTrack`-backed sink lives in `:app` — this module only defines the
 * seam and a `RecordingSink` test double (see the test sources) so the driver is unit-testable
 * on a plain JVM.
 */
interface AudioSink {
    /**
     * Called exactly once, before any [onChunk], with the sample rate the audio was generated
     * at. Callers must pass [com.phonetts.core.model.ModelDescriptor.sampleRate] here — never a
     * literal constant.
     */
    fun onFormat(sampleRate: Int)

    /** Called once per emitted chunk, in the order the flow produced them. */
    fun onChunk(samples: FloatArray)

    /** Called exactly once, after the flow has fully drained. */
    fun onEnd()
}
