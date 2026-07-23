package com.phonetts.core.audio.buffer

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Random-access scratch store that backs [GeneratedAudio]'s long-document mode: older chunks are
// evicted from the heap to this file, and read back on demand (replay, export, scrubbing) below the
// live window. Storage is RAW little-endian float32 - no quantization - so a read is byte-identical
// to the chunk that was written; the "replay from index 0 without re-synthesis" guarantee stays
// lossless whether a chunk is served from RAM or from here.
//
// Android-free (java.io only): the scratch [file] is injected. Not thread-safe on its own -
// [GeneratedAudio] serializes all access under its own lock.
private const val BYTES_PER_FLOAT = 4

class ChunkSpill(
    file: File,
    // Heap ceiling for GeneratedAudio's live window, in samples. Chunks are evicted here once the
    // retained-in-RAM sample count would exceed this. Callers derive it from sampleRate × seconds
    // (SSOT: the duration policy lives with the caller, not hard-coded here).
    val maxLiveSamples: Int,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private val target = file
    private val offsets = ArrayList<Long>()
    private val lengths = ArrayList<Int>()

    /** Append [chunk] at spill [index] (indices are dense and ascending). Returns bytes written. */
    fun write(
        index: Int,
        chunk: FloatArray,
    ) {
        require(index == offsets.size) { "spill indices must be dense/ascending: got $index, expected ${offsets.size}" }
        val offset = raf.length()
        raf.seek(offset)
        raf.write(encode(chunk))
        offsets.add(offset)
        lengths.add(chunk.size)
    }

    /** Read the chunk previously written at spill [index], reconstructed byte-identically. */
    fun read(index: Int): FloatArray {
        val length = lengths[index]
        val bytes = ByteArray(length * BYTES_PER_FLOAT)
        raf.seek(offsets[index])
        raf.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(length) { buffer.float }
    }

    override fun close() {
        runCatching { raf.close() }
        target.delete()
    }

    private fun encode(chunk: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(chunk.size * BYTES_PER_FLOAT).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in chunk) buffer.putFloat(sample)
        return buffer.array()
    }
}
