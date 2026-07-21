package com.phonetts.core.audio.buffer

import com.phonetts.core.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

// Drives an [AudioSink] from a [GeneratedAudio] buffer, one chunk at a time, and can pause/resume
// WITHOUT stopping generation (the producer keeps appending to the same buffer while paused). It
// reads by index from the retained buffer, so pausing simply stops advancing the index and
// resuming continues from exactly where it left off — no audio is dropped and nothing is
// re-synthesized. Play the same buffer again from the start to replay already-generated audio.
//
// Reading strictly one chunk at a time by index is also what keeps GeneratedAudio's long-document
// spill mode (issue #34) bounded: [GeneratedAudio.chunkAt] may serve a below-live-window index from
// the scratch file, and because playback never snapshots the whole buffer, only the current chunk
// is ever resident — a spilled chunk is read from disk, handed to the sink, and released.
class BufferedPlayback {
    private val paused = MutableStateFlow(false)
    private val stopped = MutableStateFlow(false)

    fun pause() {
        paused.value = true
    }

    fun resume() {
        paused.value = false
    }

    /** Stop this playback run for good. [play] returns after the current chunk. */
    fun stop() {
        stopped.value = true
    }

    /**
     * Feed [audio] to [sink] starting at [fromIndex]. Suspends at the live edge until more audio
     * is generated (or generation completes), and suspends while paused. Returns when the buffer
     * is fully drained and generation is complete, or [stop] is called.
     */
    suspend fun play(
        audio: GeneratedAudio,
        sampleRate: Int,
        sink: AudioSink,
        fromIndex: Int = 0,
    ) {
        sink.onFormat(sampleRate)
        var index = fromIndex
        while (!stopped.value && !fullyDrained(audio, index)) {
            index = advanceOnce(audio, sink, index)
        }
        sink.onEnd()
    }

    // True once every generated chunk has been delivered AND generation has finished.
    private fun fullyDrained(
        audio: GeneratedAudio,
        index: Int,
    ): Boolean = index >= audio.count.value && audio.complete.value

    // One step of the loop: wait out a pause, deliver the next ready chunk, or park at the live
    // edge until more is generated. Returns the (possibly advanced) playback index.
    private suspend fun advanceOnce(
        audio: GeneratedAudio,
        sink: AudioSink,
        index: Int,
    ): Int =
        when {
            paused.value -> {
                paused.first { !it || stopped.value }
                index
            }
            index < audio.count.value -> {
                sink.onChunk(audio.chunkAt(index))
                index + 1
            }
            else -> {
                awaitMore(audio, index)
                index
            }
        }

    // Suspend until the buffer grows past [index], generation completes, or playback is stopped.
    private suspend fun awaitMore(
        audio: GeneratedAudio,
        index: Int,
    ) {
        combine(audio.count, audio.complete, stopped) { count, complete, isStopped ->
            count > index || complete || isStopped
        }.first { it }
    }
}
