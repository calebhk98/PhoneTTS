package com.phonetts.core.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Drains a generated-audio `Flow<FloatArray>` into an [AudioSink] (spec §6.1). This is one of
 * the two consumers sitting on top of `synthesize()`'s single flow; [WavWriter] is the other —
 * both read [sampleRate] from the caller, who must in turn have read it from
 * [com.phonetts.core.model.ModelDescriptor.sampleRate], never a constant.
 */
class StreamingConsumer {
    /**
     * Announces the format, forwards every chunk to [sink] as it arrives, then signals the end.
     * Fully drains [flow] even if it emits zero chunks.
     */
    suspend fun play(
        flow: Flow<FloatArray>,
        sampleRate: Int,
        sink: AudioSink,
    ) {
        sink.onFormat(sampleRate)
        flow.collect { chunk -> sink.onChunk(chunk) }
        sink.onEnd()
    }
}
