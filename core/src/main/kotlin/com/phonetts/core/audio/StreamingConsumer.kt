package com.phonetts.core.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect

// One chunk of look-ahead: the upstream `synthesize()` flow already runs on Dispatchers.Default
// (AbstractVoiceEngine.flowOn), so without a buffer, `collect` still forces the fair-share
// coroutine machinery to alternate strictly generate-one/play-one — the producer can't start the
// NEXT sentence until sink.onChunk() (an AudioTrack write or file I/O) returns for the current
// one. Buffering by 1 lets generation run one chunk ahead of playback so the two genuinely
// overlap, without letting a slow consumer (a long document, a paused sink) balloon memory the
// way an unbounded buffer would (contrast [com.phonetts.core.audio.buffer.bufferAhead], which is
// deliberately unlimited because its producer is decoupled from a possibly-paused consumer via
// [com.phonetts.core.audio.buffer.GeneratedAudio]).
private const val LOOKAHEAD_CHUNKS = 1

/**
 * Drains a generated-audio `Flow<FloatArray>` into an [AudioSink] (spec §6.1). This is one of
 * the two consumers sitting on top of `synthesize()`'s single flow; [WavWriter] is the other —
 * both read [sampleRate] from the caller, who must in turn have read it from
 * [com.phonetts.core.model.ModelDescriptor.sampleRate], never a constant.
 */
class StreamingConsumer {
    /**
     * Announces the format, forwards every chunk to [sink] as it arrives, then signals the end.
     * Fully drains [flow] even if it emits zero chunks. Buffered by [LOOKAHEAD_CHUNKS] so
     * generation of the next chunk overlaps playback of the current one instead of serializing.
     */
    suspend fun play(
        flow: Flow<FloatArray>,
        sampleRate: Int,
        sink: AudioSink,
    ) {
        sink.onFormat(sampleRate)
        flow.buffer(LOOKAHEAD_CHUNKS).collect { chunk -> sink.onChunk(chunk) }
        sink.onEnd()
    }
}
