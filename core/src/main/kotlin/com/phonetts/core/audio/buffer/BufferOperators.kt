package com.phonetts.core.audio.buffer

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer

// Helpers that connect the ONE `synthesize()` flow to the buffering layer.

/**
 * Let generation run arbitrarily far ahead of the consumer. With an unbounded buffer the producer
 * never blocks on a slow (or paused) consumer, so a short sentence followed by a long one keeps
 * generating instead of stalling on a one-item buffer. Use this on the streaming path when you
 * don't need replay; use [collectInto] when you also want pause/resume and replay.
 */
fun Flow<FloatArray>.bufferAhead(): Flow<FloatArray> = buffer(capacity = Channel.UNLIMITED)

/**
 * Drain this generation flow into [audio] as fast as it emits (each append is non-blocking, so
 * generation runs at full speed regardless of playback), then mark generation complete - even if
 * the flow fails or is cancelled, so any consumer waiting at the live edge is released.
 */
suspend fun Flow<FloatArray>.collectInto(audio: GeneratedAudio) {
    try {
        collect { chunk -> audio.append(chunk) }
    } finally {
        audio.markComplete()
    }
}
