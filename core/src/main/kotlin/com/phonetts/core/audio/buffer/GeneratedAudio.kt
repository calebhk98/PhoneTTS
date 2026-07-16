package com.phonetts.core.audio.buffer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// A retained, append-only buffer of generated audio chunks. It is the single place generated
// audio lives once produced, and it is what makes the user's requirements possible:
//
//  * Unbounded ahead-generation: the producer appends as fast as it can; it never blocks on a
//    slow consumer, because appends just grow the buffer.
//  * Pause playback while generation continues: pausing stops the *consumer*, not the producer,
//    which keeps appending here.
//  * Resume already-generated audio without re-synthesis: chunks are RETAINED (not consumed once
//    like a channel), so a consumer can replay from index 0 at any time.
//
// [count] and [complete] are observable so a consumer waiting at the live edge wakes the moment
// more audio is generated or generation finishes.
class GeneratedAudio {
    private val chunks = ArrayList<FloatArray>()
    private val countState = MutableStateFlow(0)
    private val completeState = MutableStateFlow(false)

    /** Number of chunks generated so far (grows as generation proceeds). */
    val count: StateFlow<Int> = countState.asStateFlow()

    /** True once generation has finished (no more chunks will be appended). */
    val complete: StateFlow<Boolean> = completeState.asStateFlow()

    /** Append one generated chunk. Called only by the producer. */
    @Synchronized
    fun append(chunk: FloatArray) {
        check(!completeState.value) { "cannot append after generation is complete" }
        chunks.add(chunk)
        countState.value = chunks.size
    }

    /** Signal that generation has finished. Idempotent. */
    fun markComplete() {
        completeState.value = true
    }

    /** The chunk at [index]. Callers must ensure `index < count.value`. */
    @Synchronized
    fun chunkAt(index: Int): FloatArray = chunks[index]

    /** An immutable snapshot of everything generated so far, in order. */
    @Synchronized
    fun snapshot(): List<FloatArray> = chunks.toList()
}
