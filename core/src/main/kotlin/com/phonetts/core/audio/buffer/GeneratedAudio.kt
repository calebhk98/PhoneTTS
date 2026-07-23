package com.phonetts.core.audio.buffer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

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
// Long-document mode (issue #34): retaining every chunk in RAM forever is unbounded for book-length
// synthesis. Pass a [ChunkSpill] to opt in: older chunks are transparently evicted to a scratch
// FILE once the retained-in-RAM sample count crosses the spill's ceiling, and [chunkAt]/[snapshot]
// read them back below the live window. The spill is byte-lossless (raw float32), so replay-from-0
// still returns exactly what was generated - from disk or RAM, indistinguishable to consumers. With
// no spill (the default) behaviour is unchanged: everything stays in the heap.
//
// [count] and [complete] are observable so a consumer waiting at the live edge wakes the moment
// more audio is generated or generation finishes.
class GeneratedAudio(
    private val spill: ChunkSpill? = null,
) : Closeable {
    // The live window kept in RAM. In spill mode, indices below [spilledCount] have been evicted to
    // disk and are absent here; without a spill, every chunk stays here and [spilledCount] is 0.
    private val live = ArrayDeque<FloatArray>()
    private var spilledCount = 0
    private var liveSamples = 0
    private var total = 0

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
        live.addLast(chunk)
        liveSamples += chunk.size
        total++
        evictIfNeeded()
        countState.value = total
    }

    /** Signal that generation has finished. Idempotent. */
    fun markComplete() {
        completeState.value = true
    }

    /** The chunk at [index]. Callers must ensure `index < count.value`. May read from disk in spill mode. */
    @Synchronized
    fun chunkAt(index: Int): FloatArray {
        if (index >= spilledCount) return live[index - spilledCount]
        return requireSpill().read(index)
    }

    /** An immutable snapshot of everything generated so far, in order (spilled chunks read back from disk). */
    @Synchronized
    fun snapshot(): List<FloatArray> {
        val out = ArrayList<FloatArray>(total)
        for (index in 0 until spilledCount) out.add(requireSpill().read(index))
        out.addAll(live)
        return out
    }

    /** Release the scratch spill file, if any. Safe to call when no spill is configured. */
    @Synchronized
    override fun close() {
        spill?.close()
    }

    // Evict oldest live chunks to the spill until the RAM window is back under the ceiling. Always
    // keeps at least the most recent chunk resident so the live edge is served without a disk hit.
    private fun evictIfNeeded() {
        val sink = spill ?: return
        while (liveSamples > sink.maxLiveSamples && live.size > 1) {
            val oldest = live.removeFirst()
            sink.write(spilledCount, oldest)
            spilledCount++
            liveSamples -= oldest.size
        }
    }

    private fun requireSpill(): ChunkSpill = checkNotNull(spill) { "chunk below live window but no spill configured" }
}
